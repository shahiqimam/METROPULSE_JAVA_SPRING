import { CommonModule } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { LatestVehicleTelemetry, RouteStop, RouteSummary, TelemetryApiService } from '../../core/telemetry-api.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <main class="dashboard-shell">
      <header class="topbar">
        <div>
          <p class="eyebrow">MetroPulse</p>
          <h1>Transit Operations</h1>
        </div>
        <div class="toolbar-actions">
          <button class="mode-button" type="button" (click)="toggleAutoRefresh()" [class.mode-button--active]="autoRefresh()">
            {{ autoRefresh() ? 'Auto on' : 'Auto off' }}
          </button>
          <button class="refresh-button" type="button" (click)="loadLatestTelemetry()" [disabled]="loading()">
            {{ loading() ? 'Refreshing' : 'Refresh' }}
          </button>
        </div>
      </header>

      <section class="status-band" aria-label="Fleet summary">
        <div>
          <span>Tracked vehicles</span>
          <strong>{{ vehicles().length }}</strong>
        </div>
        <div>
          <span>Scheduled routes</span>
          <strong>{{ routes().length }}</strong>
        </div>
        <div>
          <span>Average speed</span>
          <strong>{{ averageSpeed() | number:'1.0-1' }} kph</strong>
        </div>
        <div>
          <span>Average load</span>
          <strong>{{ averageOccupancy() | number:'1.0-0' }}</strong>
        </div>
        <div>
          <span>Offline vehicles</span>
          <strong>{{ offlineVehicles() }}</strong>
        </div>
        <div>
          <span>Off route</span>
          <strong>{{ offRouteVehicles() }}</strong>
        </div>
        <div>
          <span>Last update</span>
          <strong>{{ lastUpdatedLabel() }}</strong>
        </div>
        <div>
          <span>Auto refresh</span>
          <strong>{{ autoRefresh() ? '10 sec' : 'Paused' }}</strong>
        </div>
      </section>

      <section class="workspace">
        <div class="side-column">
        <aside class="access-panel" aria-label="Backend access settings">
          <h2>Backend Access</h2>
          <label>
            API base
            <input [(ngModel)]="apiBase" placeholder="/api/v1" />
          </label>
          <label>
            Username
            <input [(ngModel)]="username" autocomplete="username" placeholder="user" />
          </label>
          <label>
            Password
            <input [(ngModel)]="password" autocomplete="current-password" type="password" placeholder="Spring generated password" />
          </label>
          <button class="connect-button" type="button" (click)="saveAndLoad()">Connect</button>
          <p class="hint">Docker dev credentials default to operator / metropulse-dev-password. Override them with METROPULSE_OPERATOR_USERNAME and METROPULSE_OPERATOR_PASSWORD.</p>
          <p class="error" *ngIf="error()">{{ error() }}</p>
        </aside>

        <section class="route-panel" aria-label="Scheduled routes">
          <div class="section-heading">
            <h2>Scheduled Routes</h2>
            <span>{{ selectedRouteCode() ?? routes().length + ' active seed' }}</span>
          </div>

          <div class="empty-state empty-state--compact" *ngIf="routes().length === 0">
            No routes loaded.
          </div>

          <button
            class="route-row"
            type="button"
            *ngFor="let route of routes(); trackBy: trackRoute"
            (click)="loadRouteStops(route.code)"
            [class.route-row--active]="selectedRouteCode() === route.code"
            [attr.aria-pressed]="selectedRouteCode() === route.code"
          >
            <div>
              <h3>{{ route.code }}</h3>
              <p>{{ route.shortName }}</p>
            </div>
            <dl>
              <div>
                <dt>Stops</dt>
                <dd>{{ route.stopCount }}</dd>
              </div>
              <div>
                <dt>Trips</dt>
                <dd>{{ route.tripCount }}</dd>
              </div>
              <div>
                <dt>Shape</dt>
                <dd>{{ route.routePointCount }}</dd>
              </div>
            </dl>
          </button>

          <div class="stop-pattern" *ngIf="routeStops().length > 0">
            <h3>Stop Pattern</h3>
            <ol>
              <li *ngFor="let stop of routeStops(); trackBy: trackStop">
                <span>{{ stop.stopSequence }}</span>
                <div>
                  <strong>{{ stop.stopName }}</strong>
                  <small>{{ stop.stopCode }} · {{ formatServiceTime(stop.plannedArrivalSeconds) }}</small>
                </div>
              </li>
            </ol>
          </div>
        </section>
        </div>

        <section class="fleet-panel" aria-label="Latest vehicle telemetry">
          <div class="section-heading">
            <h2>Latest Vehicle Telemetry</h2>
            <span>{{ loading() ? 'Loading' : 'Live snapshot' }}</span>
          </div>

          <div class="empty-state" *ngIf="!loading() && vehicles().length === 0">
            No telemetry rows yet.
          </div>

          <div class="vehicle-grid" *ngIf="vehicles().length > 0">
            <article class="vehicle-card" *ngFor="let vehicle of vehicles(); trackBy: trackVehicle">
              <div class="vehicle-card__header">
                <div>
                  <h3>{{ vehicle.vehicleId }}</h3>
                  <p>{{ vehicle.vehicleType }} · {{ vehicle.propulsionType }}</p>
                </div>
                <div class="pill-stack">
                  <span class="status-pill">{{ vehicle.status }}</span>
                  <span class="link-pill" [ngClass]="'link-pill--' + vehicle.connectivityState.toLowerCase()">
                    {{ vehicle.connectivityState }} · {{ vehicle.telemetryAgeSeconds | number:'1.0-0' }}s
                  </span>
                </div>
              </div>

              <dl class="metric-grid">
                <div>
                  <dt>Speed</dt>
                  <dd>{{ vehicle.speedKph | number:'1.0-1' }} kph</dd>
                </div>
                <div>
                  <dt>Load</dt>
                  <dd>{{ vehicle.occupancyEstimate }} / {{ vehicle.capacity }}</dd>
                </div>
                <div>
                  <dt>Battery</dt>
                  <dd>{{ vehicle.batteryPercent ?? 'n/a' }}<span *ngIf="vehicle.batteryPercent !== null">%</span></dd>
                </div>
                <div>
                  <dt>Heading</dt>
                  <dd>{{ vehicle.headingDegrees | number:'1.0-0' }} deg</dd>
                </div>
              </dl>

              <div class="route-progress" *ngIf="vehicle.routeCode as routeCode">
                <div class="route-progress__head">
                  <span>Route {{ routeCode }}</span>
                  <span>{{ (vehicle.routeProgress ?? 0) * 100 | number:'1.0-0' }}% along shape</span>
                </div>
                <div class="route-progress__track" role="presentation">
                  <div class="route-progress__fill" [style.width.%]="(vehicle.routeProgress ?? 0) * 100"></div>
                </div>
                <small [class.route-progress__deviation--high]="isOffRoute(vehicle)">
                  {{ vehicle.routeDeviationMeters | number:'1.0-0' }} m from route shape
                </small>
              </div>

              <div class="route-progress route-progress--empty" *ngIf="!vehicle.routeCode">
                <small>No route assignment; route progress is not projected.</small>
              </div>

              <div class="position-row">
                <span>{{ vehicle.latitude | number:'1.4-4' }}</span>
                <span>{{ vehicle.longitude | number:'1.4-4' }}</span>
              </div>
              <footer>Event {{ vehicle.sourceEventId }} · {{ vehicle.recordedAt | date:'mediumTime' }}</footer>
            </article>
          </div>
        </section>
      </section>
    </main>
  `,
  styles: [`
    :host {
      display: block;
      min-height: 100vh;
      background: #0f1720;
      color: #eef4f8;
      font-family: Inter, Arial, sans-serif;
    }

    .dashboard-shell {
      min-height: 100vh;
      padding: 24px;
    }

    .topbar,
    .status-band,
    .workspace {
      width: min(1180px, 100%);
      margin: 0 auto;
    }

    .topbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 8px 0 24px;
    }

    .eyebrow {
      margin: 0 0 4px;
      color: #65d6c6;
      font-size: 13px;
      font-weight: 700;
      text-transform: uppercase;
    }

    h1,
    h2,
    h3,
    p,
    dl,
    dd {
      margin: 0;
    }

    h1 {
      font-size: 34px;
      line-height: 1.1;
      letter-spacing: 0;
    }

    h2 {
      font-size: 18px;
      letter-spacing: 0;
    }

    button,
    input {
      font: inherit;
    }

    .refresh-button,
    .mode-button,
    .connect-button {
      border: 0;
      background: #3fc4b4;
      color: #072521;
      font-weight: 800;
      cursor: pointer;
    }

    .toolbar-actions {
      display: flex;
      gap: 10px;
      align-items: center;
    }

    .refresh-button,
    .mode-button {
      min-width: 112px;
      height: 42px;
      border-radius: 6px;
    }

    .mode-button {
      border: 1px solid #304457;
      background: #14202b;
      color: #cfe1e8;
    }

    .mode-button--active {
      border-color: #3fc4b4;
      color: #91eadf;
    }

    .refresh-button:disabled {
      cursor: progress;
      opacity: 0.68;
    }

    .status-band {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      border-top: 1px solid #233242;
      border-bottom: 1px solid #233242;
      background: #14202b;
    }

    .status-band div {
      min-height: 88px;
      padding: 18px;
      border-right: 1px solid #233242;
    }

    .status-band div:last-child {
      border-right: 0;
    }

    .status-band span,
    .section-heading span,
    .metric-grid dt,
    footer,
    .hint {
      color: #9eb0bd;
      font-size: 13px;
    }

    .status-band strong {
      display: block;
      margin-top: 8px;
      font-size: 24px;
      letter-spacing: 0;
    }

    .workspace {
      display: grid;
      grid-template-columns: 300px minmax(0, 1fr);
      gap: 20px;
      padding-top: 20px;
    }

    .side-column {
      align-self: start;
      display: grid;
      gap: 16px;
    }

    .access-panel,
    .route-panel,
    .fleet-panel,
    .vehicle-card {
      border: 1px solid #243545;
      border-radius: 8px;
      background: #14202b;
    }

    .access-panel,
    .route-panel {
      display: grid;
      gap: 14px;
      padding: 18px;
    }

    label {
      display: grid;
      gap: 6px;
      color: #ccd8df;
      font-size: 13px;
      font-weight: 700;
    }

    input {
      width: 100%;
      min-height: 40px;
      border: 1px solid #304457;
      border-radius: 6px;
      background: #0f1720;
      color: #eef4f8;
      padding: 0 11px;
    }

    .connect-button {
      min-height: 42px;
      border-radius: 6px;
    }

    .error {
      color: #ffb3a9;
      font-size: 13px;
      line-height: 1.4;
    }

    .fleet-panel {
      min-width: 0;
      padding: 18px;
    }

    .section-heading {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 14px;
    }

    .empty-state {
      min-height: 180px;
      display: grid;
      place-items: center;
      border: 1px dashed #304457;
      border-radius: 8px;
      color: #9eb0bd;
    }

    .empty-state--compact {
      min-height: 92px;
    }

    .route-row {
      display: grid;
      gap: 12px;
      width: 100%;
      border: 1px solid #243545;
      border-radius: 6px;
      background: #0f1720;
      color: inherit;
      padding: 14px;
      text-align: left;
      cursor: pointer;
    }

    .route-row--active {
      border-color: #3fc4b4;
      background: #102b2a;
    }

    .route-row h3 {
      font-size: 22px;
      letter-spacing: 0;
    }

    .route-row p {
      margin-top: 4px;
      color: #9eb0bd;
      font-size: 13px;
    }

    .route-row dl {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 8px;
    }

    .route-row dt {
      color: #9eb0bd;
      font-size: 12px;
    }

    .route-row dd {
      margin-top: 3px;
      font-size: 18px;
      font-weight: 800;
    }

    .stop-pattern {
      border-top: 1px solid #243545;
      padding-top: 14px;
    }

    .stop-pattern h3 {
      font-size: 14px;
      letter-spacing: 0;
    }

    .stop-pattern ol {
      display: grid;
      gap: 10px;
      margin: 12px 0 0;
      padding: 0;
      list-style: none;
    }

    .stop-pattern li {
      display: grid;
      grid-template-columns: 28px minmax(0, 1fr);
      gap: 10px;
      align-items: start;
    }

    .stop-pattern li > span {
      display: grid;
      place-items: center;
      width: 28px;
      height: 28px;
      border-radius: 999px;
      background: #233242;
      color: #91eadf;
      font-size: 12px;
      font-weight: 800;
    }

    .stop-pattern strong,
    .stop-pattern small {
      display: block;
    }

    .stop-pattern small {
      margin-top: 2px;
      color: #9eb0bd;
      font-size: 12px;
    }

    .pill-stack {
      display: grid;
      gap: 6px;
      justify-items: end;
    }

    .link-pill {
      border: 1px solid #304457;
      border-radius: 999px;
      color: #cfe1e8;
      font-size: 11px;
      font-weight: 800;
      padding: 4px 8px;
      white-space: nowrap;
    }

    .link-pill--online {
      border-color: #4cb9a8;
      color: #91eadf;
    }

    .link-pill--stale {
      border-color: #d1a54a;
      color: #ffd894;
    }

    .link-pill--offline {
      border-color: #c96a5e;
      color: #ffb3a9;
    }

    .route-progress {
      margin-top: 16px;
      display: grid;
      gap: 7px;
    }

    .route-progress--empty {
      min-height: 44px;
      align-content: center;
    }

    .route-progress__head {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      color: #9eb0bd;
      font-size: 12px;
      font-weight: 700;
    }

    .route-progress__track {
      height: 6px;
      border-radius: 999px;
      background: #233242;
      overflow: hidden;
    }

    .route-progress__fill {
      height: 100%;
      background: #3fc4b4;
    }

    .route-progress small {
      color: #9eb0bd;
      font-size: 12px;
    }

    .route-progress__deviation--high {
      color: #ffb3a9;
    }

    .vehicle-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 14px;
    }

    .vehicle-card {
      padding: 16px;
    }

    .vehicle-card__header,
    .position-row {
      display: flex;
      justify-content: space-between;
      gap: 12px;
    }

    .vehicle-card h3 {
      font-size: 22px;
      letter-spacing: 0;
    }

    .vehicle-card__header p {
      margin-top: 4px;
      color: #9eb0bd;
      font-size: 13px;
    }

    .status-pill {
      align-self: flex-start;
      border: 1px solid #4cb9a8;
      border-radius: 999px;
      color: #91eadf;
      font-size: 12px;
      font-weight: 800;
      padding: 5px 9px;
    }

    .metric-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
      margin-top: 18px;
    }

    .metric-grid div {
      min-height: 66px;
      border-top: 1px solid #243545;
      padding-top: 10px;
    }

    .metric-grid dd {
      margin-top: 5px;
      font-size: 20px;
      font-weight: 800;
    }

    .position-row {
      margin-top: 16px;
      padding: 10px 0;
      border-top: 1px solid #243545;
      border-bottom: 1px solid #243545;
      color: #d8e5eb;
      font-family: Consolas, monospace;
      font-size: 13px;
    }

    footer {
      margin-top: 12px;
    }

    @media (max-width: 820px) {
      .dashboard-shell {
        padding: 16px;
      }

      .topbar,
      .workspace {
        grid-template-columns: 1fr;
      }

      .topbar {
        align-items: flex-start;
        flex-direction: column;
      }

      .status-band {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }

      .status-band div {
        border-bottom: 1px solid #233242;
      }

      .status-band div:nth-child(2n) {
        border-right: 0;
      }

      .status-band div:last-child {
        grid-column: 1 / -1;
        border-bottom: 0;
      }

      .toolbar-actions {
        width: 100%;
      }

      .refresh-button,
      .mode-button {
        flex: 1;
      }
    }
  `]
})
export class DashboardComponent implements OnDestroy {
  private readonly telemetryApi = inject(TelemetryApiService);

  protected apiBase = sessionStorage.getItem('metropulse.apiBase') ?? '/api/v1';
  protected username = sessionStorage.getItem('metropulse.username') ?? 'operator';
  protected password = sessionStorage.getItem('metropulse.password') ?? 'metropulse-dev-password';

  protected readonly vehicles = signal<LatestVehicleTelemetry[]>([]);
  protected readonly routes = signal<RouteSummary[]>([]);
  protected readonly routeStops = signal<RouteStop[]>([]);
  protected readonly selectedRouteCode = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly lastUpdated = signal<Date | null>(null);
  protected readonly autoRefresh = signal(sessionStorage.getItem('metropulse.autoRefresh') !== 'false');

  private refreshTimer: number | undefined;

  /** Project threshold: more than 100 m from the assigned route shape is treated as off route. */
  protected static readonly ROUTE_DEVIATION_METERS = 100;

  protected readonly averageSpeed = computed(() => this.average((vehicle) => vehicle.speedKph));
  protected readonly averageOccupancy = computed(() => this.average((vehicle) => vehicle.occupancyEstimate));
  protected readonly offlineVehicles = computed(
    () => this.vehicles().filter((vehicle) => vehicle.connectivityState === 'OFFLINE').length
  );
  protected readonly offRouteVehicles = computed(
    () => this.vehicles().filter((vehicle) => this.isOffRoute(vehicle)).length
  );
  protected readonly lastUpdatedLabel = computed(() => {
    const value = this.lastUpdated();
    return value ? value.toLocaleTimeString() : 'Waiting';
  });

  constructor() {
    this.loadRoutes();
    this.loadLatestTelemetry();
    this.configureAutoRefresh();
  }

  ngOnDestroy(): void {
    this.clearAutoRefresh();
  }

  protected toggleAutoRefresh(): void {
    this.autoRefresh.update((enabled) => !enabled);
    sessionStorage.setItem('metropulse.autoRefresh', String(this.autoRefresh()));
    this.configureAutoRefresh();
  }

  protected saveAndLoad(): void {
    sessionStorage.setItem('metropulse.apiBase', this.apiBase);
    sessionStorage.setItem('metropulse.username', this.username);
    sessionStorage.setItem('metropulse.password', this.password);
    this.loadRoutes();
    this.loadLatestTelemetry();
  }

  protected loadLatestTelemetry(): void {
    if (this.loading()) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.telemetryApi.findLatestVehicleTelemetry(this.apiBase, this.username, this.password).pipe(
      finalize(() => this.loading.set(false))
    ).subscribe({
      next: (vehicles) => {
        this.vehicles.set(vehicles);
        this.lastUpdated.set(new Date());
      },
      error: (error) => {
        this.error.set(this.errorMessage(error.status));
      }
    });
  }

  protected isOffRoute(vehicle: LatestVehicleTelemetry): boolean {
    return (
      vehicle.routeDeviationMeters !== null &&
      vehicle.routeDeviationMeters > DashboardComponent.ROUTE_DEVIATION_METERS
    );
  }

  protected trackVehicle(_index: number, vehicle: LatestVehicleTelemetry): string {
    return vehicle.vehicleId;
  }

  protected trackRoute(_index: number, route: RouteSummary): string {
    return route.code;
  }

  protected trackStop(_index: number, stop: RouteStop): string {
    return stop.stopCode;
  }

  protected loadRouteStops(routeCode: string): void {
    this.selectedRouteCode.set(routeCode);
    this.telemetryApi.findRouteStops(this.apiBase, this.username, this.password, routeCode).subscribe({
      next: (stops) => this.routeStops.set(stops),
      error: () => this.routeStops.set([])
    });
  }

  protected formatServiceTime(totalSeconds: number): string {
    const hours = Math.floor(totalSeconds / 3600) % 24;
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
  }

  private loadRoutes(): void {
    this.telemetryApi.findRoutes(this.apiBase, this.username, this.password).subscribe({
      next: (routes) => {
        this.routes.set(routes);
        if (routes.length > 0) {
          this.loadRouteStops(routes[0].code);
        } else {
          this.selectedRouteCode.set(null);
          this.routeStops.set([]);
        }
      },
      error: () => {
        this.routes.set([]);
        this.selectedRouteCode.set(null);
        this.routeStops.set([]);
      }
    });
  }

  private configureAutoRefresh(): void {
    this.clearAutoRefresh();

    if (!this.autoRefresh()) {
      return;
    }

    this.refreshTimer = window.setInterval(() => this.loadLatestTelemetry(), 10000);
  }

  private clearAutoRefresh(): void {
    if (this.refreshTimer === undefined) {
      return;
    }

    window.clearInterval(this.refreshTimer);
    this.refreshTimer = undefined;
  }

  private average(project: (vehicle: LatestVehicleTelemetry) => number): number {
    const values = this.vehicles().map(project);
    if (values.length === 0) {
      return 0;
    }
    return values.reduce((sum, value) => sum + value, 0) / values.length;
  }

  private errorMessage(status: number): string {
    if (status === 401) {
      return 'Backend rejected the credentials. Check the generated development password in backend logs.';
    }
    if (status === 0) {
      return 'Frontend cannot reach the backend. Confirm Docker is running and the dev proxy is active.';
    }
    return `Backend request failed with status ${status}.`;
  }
}
