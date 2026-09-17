import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';

interface LatestVehicleTelemetry {
  vehicleId: string;
  vehicleType: string;
  propulsionType: string;
  capacity: number;
  status: string;
  sourceEventId: string;
  recordedAt: string;
  receivedAt: string;
  latitude: number;
  longitude: number;
  speedKph: number;
  headingDegrees: number;
  occupancyEstimate: number;
  batteryPercent: number | null;
}

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
          <span>Average speed</span>
          <strong>{{ averageSpeed() | number:'1.0-1' }} kph</strong>
        </div>
        <div>
          <span>Average load</span>
          <strong>{{ averageOccupancy() | number:'1.0-0' }}</strong>
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
                <span class="status-pill">{{ vehicle.status }}</span>
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
      grid-template-columns: repeat(5, minmax(0, 1fr));
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

    .access-panel,
    .fleet-panel,
    .vehicle-card {
      border: 1px solid #243545;
      border-radius: 8px;
      background: #14202b;
    }

    .access-panel {
      align-self: start;
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
  private readonly http = inject(HttpClient);

  protected apiBase = sessionStorage.getItem('metropulse.apiBase') ?? '/api/v1';
  protected username = sessionStorage.getItem('metropulse.username') ?? 'operator';
  protected password = sessionStorage.getItem('metropulse.password') ?? '';

  protected readonly vehicles = signal<LatestVehicleTelemetry[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly lastUpdated = signal<Date | null>(null);
  protected readonly autoRefresh = signal(sessionStorage.getItem('metropulse.autoRefresh') !== 'false');

  private refreshTimer: number | undefined;

  protected readonly averageSpeed = computed(() => this.average((vehicle) => vehicle.speedKph));
  protected readonly averageOccupancy = computed(() => this.average((vehicle) => vehicle.occupancyEstimate));
  protected readonly lastUpdatedLabel = computed(() => {
    const value = this.lastUpdated();
    return value ? value.toLocaleTimeString() : 'Waiting';
  });

  constructor() {
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
    this.loadLatestTelemetry();
  }

  protected loadLatestTelemetry(): void {
    if (this.loading()) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.http.get<LatestVehicleTelemetry[]>(`${this.apiBase}/telemetry/vehicles/latest`, {
      headers: this.authHeaders()
    }).pipe(
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

  protected trackVehicle(_index: number, vehicle: LatestVehicleTelemetry): string {
    return vehicle.vehicleId;
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

  private authHeaders(): HttpHeaders {
    if (!this.username || !this.password) {
      return new HttpHeaders();
    }

    return new HttpHeaders({
      Authorization: `Basic ${btoa(`${this.username}:${this.password}`)}`
    });
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
