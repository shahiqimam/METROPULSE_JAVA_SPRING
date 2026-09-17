import { CommonModule } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';
import {
  LatestVehicleTelemetry,
  RouteGeometryPoint,
  OperationalAlert,
  RouteHeadwaySnapshot,
  RouteStop,
  RouteSummary,
  TelemetryApiService
} from '../../core/telemetry-api.service';
import { AlertsPanelComponent } from './components/alerts-panel.component';
import { FleetPanelComponent } from './components/fleet-panel.component';
import { HeadwayPanelComponent } from './components/headway-panel.component';
import { Metric, MetricBarComponent } from './components/metric-bar.component';
import { NetworkMapComponent } from './components/network-map.component';
import { RoutePanelComponent } from './components/route-panel.component';
import { SettingsDrawerComponent } from './components/settings-drawer.component';
import { isLowBattery, isOffRoute } from './vehicle-status';

const REFRESH_INTERVAL_MS = 5000;

/**
 * The control-centre shell: loads the network picture and lays it out.
 *
 * <p>Every number on screen comes from stored data through the API. The dashboard derives counts and
 * averages from that snapshot, but it does not invent state: if the backend has not projected a
 * vehicle yet, the vehicle is not here.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    AlertsPanelComponent,
    CommonModule,
    FleetPanelComponent,
    HeadwayPanelComponent,
    MetricBarComponent,
    NetworkMapComponent,
    RoutePanelComponent,
    SettingsDrawerComponent
  ],
  template: `
    <div class="shell">
      <header class="topbar">
        <div class="brand">
          <span class="brand__mark" aria-hidden="true"></span>
          <span class="brand__text">
            <strong>MetroPulse</strong>
            <small>Network Operations</small>
          </span>
        </div>

        <div class="topbar__status">
          <span class="live" [attr.data-state]="connectionState()">
            <span class="live__dot"></span>{{ connectionLabel() }}
          </span>
          <span class="clock">{{ lastUpdatedLabel() }}</span>
        </div>

        <div class="topbar__actions">
          <button
            type="button"
            class="ghost"
            (click)="toggleAutoRefresh()"
            [class.ghost--on]="autoRefresh()"
            [attr.aria-pressed]="autoRefresh()"
          >
            {{ autoRefresh() ? 'Auto 5s' : 'Auto off' }}
          </button>
          <button type="button" class="ghost" (click)="refresh()" [disabled]="loading()">
            {{ loading() ? 'Refreshing…' : 'Refresh' }}
          </button>
          <button type="button" class="ghost ghost--icon" (click)="settingsOpen.set(true)" aria-label="Connection settings">
            ⚙
          </button>
        </div>
      </header>

      <div class="banner" *ngIf="error() as message" role="alert">
        <span>{{ message }}</span>
        <button type="button" (click)="settingsOpen.set(true)">Connection settings</button>
      </div>

      <app-metric-bar [metrics]="metrics()" />

      <main class="workspace">
        <app-network-map
          [routeCode]="selectedRouteCode()"
          [geometry]="routeGeometry()"
          [stops]="routeStops()"
          [vehicles]="vehicles()"
          [selectedVehicleId]="selectedVehicleId()"
          [hoveredVehicleId]="hoveredVehicleId()"
          (selected)="selectVehicle($event)"
          (hovered)="hoveredVehicleId.set($event)"
        />

        <div class="sidebar">
          <app-alerts-panel
            [alerts]="alerts()"
            (acknowledged)="acknowledgeAlert($event)"
            (closed)="closeAlert($event)"
          />
          <app-headway-panel [snapshot]="headway()" />
          <app-fleet-panel
            [vehicles]="vehicles()"
            [selectedVehicleId]="selectedVehicleId()"
            (selected)="selectVehicle($event)"
            (hovered)="hoveredVehicleId.set($event)"
          />
          <app-route-panel
            [routes]="routes()"
            [stops]="routeStops()"
            [selectedRouteCode]="selectedRouteCode()"
            (routeSelected)="selectRoute($event)"
          />
        </div>
      </main>

      <footer class="footnote">
        Synthetic data. MetroPulse is not a real transit, dispatch or passenger-information system.
      </footer>
    </div>

    <app-settings-drawer
      [open]="settingsOpen()"
      [error]="error()"
      [(apiBase)]="apiBase"
      [(username)]="username"
      [(password)]="password"
      (closed)="settingsOpen.set(false)"
      (applied)="applySettings()"
    />
  `,
  styles: [`
    :host {
      display: block;
      min-height: 100vh;
      background: var(--plane);
    }

    .shell {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-height: 100vh;
      padding: 14px 18px 12px;
    }

    .topbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
      padding-bottom: 12px;
      border-bottom: 1px solid var(--hairline);
    }

    .brand {
      display: flex;
      align-items: center;
      gap: 11px;
    }

    .brand__mark {
      width: 12px;
      height: 26px;
      border-radius: 3px;
      background: linear-gradient(180deg, var(--series-1), var(--series-3));
    }

    .brand__text {
      display: grid;
    }

    .brand__text strong {
      font-size: 17px;
      font-weight: 700;
      letter-spacing: -0.01em;
    }

    .brand__text small {
      color: var(--ink-muted);
      font-size: 11px;
      letter-spacing: 0.1em;
      text-transform: uppercase;
    }

    .topbar__status {
      display: flex;
      align-items: center;
      gap: 14px;
      margin-right: auto;
      margin-left: 8px;
    }

    .live {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      padding: 4px 10px;
      border: 1px solid var(--hairline-strong);
      border-radius: 999px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .live__dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--ink-muted);
    }

    .live[data-state='live'] {
      color: var(--status-good);
      border-color: color-mix(in srgb, var(--status-good) 45%, transparent);
    }

    .live[data-state='live'] .live__dot {
      background: var(--status-good);
      animation: pulse 2s ease-in-out infinite;
    }

    .live[data-state='stale'] {
      color: var(--status-warning);
      border-color: color-mix(in srgb, var(--status-warning) 45%, transparent);
    }

    .live[data-state='stale'] .live__dot { background: var(--status-warning); }

    .live[data-state='down'] {
      color: var(--status-critical);
      border-color: color-mix(in srgb, var(--status-critical) 45%, transparent);
    }

    .live[data-state='down'] .live__dot { background: var(--status-critical); }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.35; }
    }

    @media (prefers-reduced-motion: reduce) {
      .live[data-state='live'] .live__dot { animation: none; }
    }

    .clock {
      color: var(--ink-muted);
      font-family: var(--font-mono);
      font-size: 12px;
      font-variant-numeric: tabular-nums;
    }

    .topbar__actions {
      display: flex;
      gap: 8px;
    }

    .ghost {
      height: 34px;
      min-width: 92px;
      padding: 0 14px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface);
      color: var(--ink-secondary);
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }

    .ghost:hover:not(:disabled) {
      color: var(--ink-primary);
      border-color: var(--ink-muted);
    }

    .ghost:disabled {
      cursor: progress;
      opacity: 0.6;
    }

    .ghost--on {
      color: var(--series-1);
      border-color: color-mix(in srgb, var(--series-1) 55%, transparent);
    }

    .ghost--icon {
      min-width: 34px;
      padding: 0;
      font-size: 15px;
    }

    .banner {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 14px;
      border: 1px solid color-mix(in srgb, var(--status-critical) 45%, transparent);
      border-radius: var(--radius-md);
      background: color-mix(in srgb, var(--status-critical) 12%, var(--surface));
      color: var(--ink-primary);
      font-size: 13px;
    }

    .banner button {
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface);
      padding: 5px 10px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      white-space: nowrap;
    }

    .workspace {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 400px;
      gap: 12px;
      flex: 1;
      min-height: 0;
    }

    .sidebar {
      display: grid;
      grid-template-rows: auto auto minmax(0, 2fr) minmax(0, 1fr);
      gap: 12px;
      min-height: 0;
    }

    .footnote {
      color: var(--ink-muted);
      font-size: 11px;
      text-align: center;
    }

    @media (max-width: 1080px) {
      .workspace {
        grid-template-columns: minmax(0, 1fr);
      }

      .sidebar {
        grid-template-rows: none;
      }

      app-network-map {
        min-height: 420px;
      }
    }

    @media (max-width: 640px) {
      .shell {
        padding: 12px;
      }

      .topbar__status {
        order: 3;
        width: 100%;
        margin: 0;
      }
    }
  `]
})
export class DashboardComponent implements OnDestroy {
  private readonly telemetryApi = inject(TelemetryApiService);

  protected readonly apiBase = signal(sessionStorage.getItem('metropulse.apiBase') ?? '/api/v1');
  protected readonly username = signal(sessionStorage.getItem('metropulse.username') ?? 'operator');
  protected readonly password = signal(sessionStorage.getItem('metropulse.password') ?? 'metropulse-dev-password');

  protected readonly vehicles = signal<LatestVehicleTelemetry[]>([]);
  protected readonly routes = signal<RouteSummary[]>([]);
  protected readonly routeStops = signal<RouteStop[]>([]);
  protected readonly routeGeometry = signal<RouteGeometryPoint[]>([]);
  protected readonly headway = signal<RouteHeadwaySnapshot | null>(null);
  protected readonly alerts = signal<OperationalAlert[]>([]);
  protected readonly selectedRouteCode = signal<string | null>(null);
  protected readonly selectedVehicleId = signal<string | null>(null);
  protected readonly hoveredVehicleId = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly lastUpdated = signal<Date | null>(null);
  protected readonly settingsOpen = signal(false);
  protected readonly autoRefresh = signal(sessionStorage.getItem('metropulse.autoRefresh') !== 'false');

  private refreshTimer: number | undefined;

  protected readonly connectionState = computed(() => {
    if (this.error()) {
      return 'down';
    }
    return this.vehicles().some((vehicle) => vehicle.connectivityState === 'ONLINE') ? 'live' : 'stale';
  });

  protected readonly connectionLabel = computed(() => {
    switch (this.connectionState()) {
      case 'down':
        return 'No data';
      case 'stale':
        return 'No fresh telemetry';
      default:
        return 'Live';
    }
  });

  protected readonly lastUpdatedLabel = computed(() => {
    const value = this.lastUpdated();
    return value ? `Updated ${value.toLocaleTimeString()}` : 'Waiting for data';
  });

  protected readonly metrics = computed<Metric[]>(() => {
    const fleet = this.vehicles();
    const offline = fleet.filter((vehicle) => vehicle.connectivityState === 'OFFLINE').length;
    const stale = fleet.filter((vehicle) => vehicle.connectivityState === 'STALE').length;
    const offRoute = fleet.filter(isOffRoute).length;
    const lowBattery = fleet.filter(isLowBattery).length;

    return [
      { label: 'Vehicles', value: String(fleet.length), detail: `${this.routes().length} route${this.routes().length === 1 ? '' : 's'} in service` },
      { label: 'On route', value: String(fleet.length - offRoute - offline), detail: 'within 100 m of shape' },
      { label: 'Off route', value: String(offRoute), detail: 'over 100 m from shape', role: offRoute > 0 ? 'serious' : null },
      { label: 'No telemetry', value: String(offline), detail: `${stale} stale`, role: offline > 0 ? 'critical' : null },
      { label: 'Low battery', value: String(lowBattery), detail: 'at or under 20%', role: lowBattery > 0 ? 'serious' : null },
      {
        label: 'Live alerts',
        value: String(this.alerts().length),
        detail: this.unacknowledgedCount() > 0 ? `${this.unacknowledgedCount()} unacknowledged` : 'all acknowledged',
        role: this.criticalOrMajorCount() > 0 ? 'critical' : null
      },
      {
        label: 'Headway',
        value: String(this.sustainedConditions()),
        detail: this.watchedConditions() > 0 ? `${this.watchedConditions()} being watched` : 'pairs out of range',
        role: this.sustainedConditions() > 0 ? 'critical' : null
      },
      { label: 'Avg speed', value: `${this.averageSpeed().toFixed(1)}`, detail: 'kph across fleet' },
      { label: 'Avg load', value: `${Math.round(this.averageOccupancy())}`, detail: 'passengers on board' }
    ];
  });

  private readonly unacknowledgedCount = computed(
    () => this.alerts().filter((alert) => alert.status === 'OPEN').length
  );
  private readonly criticalOrMajorCount = computed(
    () => this.alerts().filter((alert) => alert.severity !== 'MINOR').length
  );

  private readonly sustainedConditions = computed(
    () => (this.headway()?.conditions ?? []).filter((condition) => condition.confirmed).length
  );
  private readonly watchedConditions = computed(
    () => (this.headway()?.conditions ?? []).filter((condition) => !condition.confirmed).length
  );

  private readonly averageSpeed = computed(() => this.average((vehicle) => vehicle.speedKph));
  private readonly averageOccupancy = computed(() => this.average((vehicle) => vehicle.occupancyEstimate));

  constructor() {
    this.loadRoutes();
    this.refresh();
    this.configureAutoRefresh();
  }

  ngOnDestroy(): void {
    this.clearAutoRefresh();
  }

  protected refresh(): void {
    if (this.loading()) {
      return;
    }

    this.loading.set(true);

    this.telemetryApi
      .findLatestVehicleTelemetry(this.apiBase(), this.username(), this.password())
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (vehicles) => {
          this.vehicles.set(vehicles);
          this.lastUpdated.set(new Date());
          this.error.set(null);
        },
        error: (error) => this.error.set(this.errorMessage(error.status))
      });

    this.refreshHeadway();
    this.refreshAlerts();
  }

  protected acknowledgeAlert(alertId: number): void {
    this.telemetryApi
      .acknowledgeAlert(this.apiBase(), this.username(), this.password(), alertId)
      .subscribe({ next: () => this.refreshAlerts(), error: () => this.refreshAlerts() });
  }

  protected closeAlert(alertId: number): void {
    this.telemetryApi
      .closeAlert(this.apiBase(), this.username(), this.password(), alertId)
      .subscribe({ next: () => this.refreshAlerts(), error: () => this.refreshAlerts() });
  }

  private refreshAlerts(): void {
    this.telemetryApi.findAlerts(this.apiBase(), this.username(), this.password()).subscribe({
      next: (alerts) => this.alerts.set(alerts),
      error: () => this.alerts.set([])
    });
  }

  private refreshHeadway(): void {
    const routeCode = this.selectedRouteCode();
    if (!routeCode) {
      this.headway.set(null);
      return;
    }

    this.telemetryApi
      .findRouteHeadway(this.apiBase(), this.username(), this.password(), routeCode)
      .subscribe({
        next: (snapshot) => this.headway.set(snapshot),
        error: () => this.headway.set(null)
      });
  }

  protected toggleAutoRefresh(): void {
    this.autoRefresh.update((enabled) => !enabled);
    sessionStorage.setItem('metropulse.autoRefresh', String(this.autoRefresh()));
    this.configureAutoRefresh();
  }

  protected applySettings(): void {
    sessionStorage.setItem('metropulse.apiBase', this.apiBase());
    sessionStorage.setItem('metropulse.username', this.username());
    sessionStorage.setItem('metropulse.password', this.password());
    this.settingsOpen.set(false);
    this.loadRoutes();
    this.refresh();
  }

  protected selectVehicle(vehicleId: string): void {
    this.selectedVehicleId.update((current) => (current === vehicleId ? null : vehicleId));
  }

  protected selectRoute(routeCode: string): void {
    this.selectedRouteCode.set(routeCode);
    this.loadRouteDetail(routeCode);
    this.refreshHeadway();
  }

  private loadRoutes(): void {
    this.telemetryApi.findRoutes(this.apiBase(), this.username(), this.password()).subscribe({
      next: (routes) => {
        this.routes.set(routes);
        if (routes.length > 0) {
          this.selectRoute(routes[0].code);
        } else {
          this.selectedRouteCode.set(null);
          this.routeStops.set([]);
          this.routeGeometry.set([]);
        }
      },
      error: () => {
        this.routes.set([]);
        this.selectedRouteCode.set(null);
        this.routeStops.set([]);
        this.routeGeometry.set([]);
      }
    });
  }

  private loadRouteDetail(routeCode: string): void {
    this.telemetryApi.findRouteStops(this.apiBase(), this.username(), this.password(), routeCode).subscribe({
      next: (stops) => this.routeStops.set(stops),
      error: () => this.routeStops.set([])
    });

    this.telemetryApi.findRouteGeometry(this.apiBase(), this.username(), this.password(), routeCode).subscribe({
      next: (geometry) => this.routeGeometry.set(geometry),
      error: () => this.routeGeometry.set([])
    });
  }

  private configureAutoRefresh(): void {
    this.clearAutoRefresh();
    if (!this.autoRefresh()) {
      return;
    }
    this.refreshTimer = window.setInterval(() => this.refresh(), REFRESH_INTERVAL_MS);
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
      return 'The backend rejected these credentials.';
    }
    if (status === 0) {
      return 'Cannot reach the backend. Check that the stack is running.';
    }
    return `Backend request failed with status ${status}.`;
  }
}
