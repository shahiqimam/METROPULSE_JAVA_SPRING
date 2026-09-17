import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import {
  Charger,
  ChargingSession,
  LatestVehicleTelemetry,
  TelemetryApiService
} from '../../core/telemetry-api.service';
import { AppShellComponent } from '../../shared/app-shell.component';
import { formatAge, isLowBattery } from '../dashboard/vehicle-status';

/**
 * Depot and charging operations.
 *
 * <p>Plugging a vehicle in is the one action here, and it is the one place in the system where two
 * controllers can genuinely collide. The backend settles that with a row lock; this screen just shows
 * what happened, including the refusal.
 */
@Component({
  selector: 'app-ev',
  standalone: true,
  imports: [CommonModule, FormsModule, AppShellComponent],
  template: `
    <app-shell>
      <main class="page">
        <header class="page__head">
          <div>
            <h1>EV operations</h1>
            <p>{{ available() }} of {{ chargers().length }} chargers available · {{ activeSessions().length }} charging</p>
          </div>
          <button type="button" class="ghost" (click)="load()">Refresh</button>
        </header>

        <p class="banner" *ngIf="message() as text" [attr.data-kind]="messageKind()">{{ text }}</p>

        <section class="panel">
          <h2>Chargers</h2>
          <div class="chargers">
            <article *ngFor="let charger of chargers(); trackBy: trackCharger" [attr.data-status]="charger.status">
              <div class="charger__head">
                <span class="charger__code">{{ charger.code }}</span>
                <span class="charger__status">{{ label(charger.status) }}</span>
              </div>
              <p class="charger__depot">{{ charger.depotName }} · {{ charger.powerKw }} kW</p>

              <p class="charger__vehicle" *ngIf="charger.occupyingVehicleId">
                Charging {{ charger.occupyingVehicleId }}
              </p>

              <div class="charger__action" *ngIf="canAct() && charger.status === 'AVAILABLE'">
                <select [(ngModel)]="selectedVehicle[charger.code]">
                  <option value="">Select vehicle</option>
                  <option *ngFor="let vehicle of chargeableVehicles()" [value]="vehicle.vehicleId">
                    {{ vehicle.vehicleId }} · {{ vehicle.batteryPercent }}%
                  </option>
                </select>
                <button
                  type="button"
                  [disabled]="!selectedVehicle[charger.code]"
                  (click)="startSession(charger)"
                >
                  Plug in
                </button>
              </div>

              <button
                type="button"
                class="ghost ghost--small"
                *ngIf="canAct() && charger.status === 'OCCUPIED' && sessionFor(charger) as session"
                (click)="complete(session)"
              >
                Complete session
              </button>
            </article>
          </div>
        </section>

        <section class="panel">
          <h2>Fleet battery</h2>
          <ul class="batteries">
            <li *ngFor="let vehicle of vehicles(); trackBy: trackVehicle" [class.low]="low(vehicle)">
              <span class="battery__id">{{ vehicle.vehicleId }}</span>
              <span class="battery__bar">
                <span class="battery__fill" [style.width.%]="vehicle.batteryPercent ?? 0"></span>
              </span>
              <span class="battery__value">{{ vehicle.batteryPercent ?? '—' }}%</span>
              <span class="battery__age">{{ age(vehicle) }} ago</span>
            </li>
          </ul>
        </section>

        <section class="panel">
          <h2>Recent sessions</h2>
          <p class="empty" *ngIf="sessions().length === 0">No charging sessions yet.</p>
          <table *ngIf="sessions().length > 0">
            <thead>
              <tr>
                <th>Vehicle</th><th>Charger</th><th>Status</th><th>Started</th><th>Charge</th><th>By</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let session of sessions(); trackBy: trackSession">
                <td class="mono">{{ session.vehicleId }}</td>
                <td class="mono">{{ session.chargerCode }}</td>
                <td>{{ label(session.status) }}</td>
                <td>{{ session.startedAt | date: 'short' }}</td>
                <td>
                  {{ session.startBatteryPercent ?? '—' }}%
                  <ng-container *ngIf="session.endBatteryPercent !== null">
                    → {{ session.endBatteryPercent }}%
                  </ng-container>
                </td>
                <td>{{ session.startedBy }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </main>
    </app-shell>
  `,
  styles: [`
    .page {
      width: min(1100px, 100%);
      margin: 0 auto;
      padding: 20px 18px 40px;
      display: grid;
      gap: 16px;
    }

    .page__head {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: 16px;
    }

    h1 {
      font-size: 22px;
      font-weight: 700;
    }

    .page__head p {
      margin-top: 2px;
      color: var(--ink-muted);
      font-size: 13px;
    }

    .panel {
      padding: 16px 18px;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }

    h2 {
      margin-bottom: 12px;
      font-size: 12px;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--ink-secondary);
    }

    .chargers {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
    }

    .chargers article {
      padding: 13px 14px;
      border: 1px solid var(--hairline);
      border-left: 3px solid var(--ink-muted);
      border-radius: var(--radius-md);
      background: var(--surface-sunken);
    }

    article[data-status='AVAILABLE'] { border-left-color: var(--status-good); }
    article[data-status='OCCUPIED'] { border-left-color: var(--series-1); }
    article[data-status='MAINTENANCE'] { border-left-color: var(--status-warning); }
    article[data-status='OFFLINE'] { border-left-color: var(--status-critical); }

    .charger__head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 8px;
    }

    .charger__code {
      font-family: var(--font-mono);
      font-size: 14px;
      font-weight: 700;
    }

    .charger__status {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--ink-muted);
    }

    article[data-status='AVAILABLE'] .charger__status { color: var(--status-good); }
    article[data-status='OCCUPIED'] .charger__status { color: var(--series-1); }

    .charger__depot {
      margin-top: 3px;
      color: var(--ink-muted);
      font-size: 11px;
    }

    .charger__vehicle {
      margin-top: 8px;
      font-family: var(--font-mono);
      font-size: 12px;
      color: var(--ink-secondary);
    }

    .charger__action {
      display: grid;
      gap: 6px;
      margin-top: 10px;
    }

    select,
    input {
      height: 30px;
      padding: 0 8px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface);
      font-size: 12px;
    }

    button {
      height: 30px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-raised);
      color: var(--ink-secondary);
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }

    button:hover:not(:disabled) {
      color: var(--ink-primary);
      border-color: var(--ink-muted);
    }

    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .ghost--small {
      margin-top: 10px;
      width: 100%;
    }

    .batteries {
      display: grid;
      gap: 8px;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .batteries li {
      display: grid;
      grid-template-columns: 90px minmax(0, 1fr) 50px 90px;
      align-items: center;
      gap: 10px;
      font-size: 12px;
    }

    .battery__id {
      font-family: var(--font-mono);
      font-weight: 600;
    }

    .battery__bar {
      height: 6px;
      border-radius: 999px;
      background: var(--surface-sunken);
      overflow: hidden;
    }

    .battery__fill {
      display: block;
      height: 100%;
      background: var(--status-good);
    }

    .low .battery__fill { background: var(--status-serious); }
    .low .battery__value { color: var(--status-serious); font-weight: 700; }

    .battery__value {
      font-variant-numeric: tabular-nums;
      text-align: right;
    }

    .battery__age {
      color: var(--ink-muted);
      text-align: right;
    }

    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
    }

    th {
      padding: 6px 8px;
      border-bottom: 1px solid var(--hairline);
      color: var(--ink-muted);
      font-size: 10px;
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      text-align: left;
    }

    td {
      padding: 7px 8px;
      border-bottom: 1px solid var(--hairline);
    }

    .mono {
      font-family: var(--font-mono);
    }

    .empty {
      padding: 20px;
      color: var(--ink-muted);
      text-align: center;
      font-size: 13px;
    }

    .banner {
      padding: 10px 14px;
      border-radius: var(--radius-md);
      font-size: 13px;
    }

    .banner[data-kind='error'] {
      border: 1px solid color-mix(in srgb, var(--status-critical) 45%, transparent);
      color: var(--status-critical);
    }

    .banner[data-kind='ok'] {
      border: 1px solid color-mix(in srgb, var(--status-good) 45%, transparent);
      color: var(--status-good);
    }

    .ghost {
      height: 32px;
      padding: 0 12px;
    }
  `]
})
export class EvComponent {
  private readonly api = inject(TelemetryApiService);
  private readonly auth = inject(AuthService);

  protected readonly chargers = signal<Charger[]>([]);
  protected readonly sessions = signal<ChargingSession[]>([]);
  protected readonly vehicles = signal<LatestVehicleTelemetry[]>([]);
  protected readonly message = signal<string | null>(null);
  protected readonly messageKind = signal<'ok' | 'error'>('ok');

  protected selectedVehicle: Record<string, string> = {};

  protected readonly canAct = computed(() => {
    const role = this.auth.user()?.role;
    return role === 'CONTROLLER' || role === 'ADMIN' || role === 'FLEET_SUPERVISOR';
  });

  protected readonly available = computed(
    () => this.chargers().filter((charger) => charger.status === 'AVAILABLE').length
  );
  protected readonly activeSessions = computed(
    () => this.sessions().filter((session) => session.status === 'ACTIVE')
  );
  /** Vehicles not already plugged in somewhere. */
  protected readonly chargeableVehicles = computed(() => {
    const charging = new Set(this.activeSessions().map((session) => session.vehicleId));
    return this.vehicles().filter((vehicle) => !charging.has(vehicle.vehicleId));
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.api.findChargers().subscribe({ next: (chargers) => this.chargers.set(chargers) });
    this.api.findChargingSessions().subscribe({ next: (sessions) => this.sessions.set(sessions) });
    this.api.findLatestVehicleTelemetry().subscribe({ next: (vehicles) => this.vehicles.set(vehicles) });
  }

  protected sessionFor(charger: Charger): ChargingSession | undefined {
    return this.activeSessions().find((session) => session.chargerCode === charger.code);
  }

  protected startSession(charger: Charger): void {
    const vehicleId = this.selectedVehicle[charger.code];
    if (!vehicleId) {
      return;
    }

    this.api.startChargingSession(charger.code, vehicleId).subscribe({
      next: (session) => {
        this.show(`${session.vehicleId} is charging on ${session.chargerCode}.`, 'ok');
        this.selectedVehicle[charger.code] = '';
        this.load();
      },
      error: (response) => {
        // 409 here is the charger lock doing its job: somebody else got there first.
        this.show(
          response.status === 409
            ? response.error?.message ?? 'That charger is no longer available.'
            : 'Could not start the session.',
          'error'
        );
        this.load();
      }
    });
  }

  protected complete(session: ChargingSession): void {
    this.api.completeChargingSession(session.id).subscribe({
      next: () => {
        this.show(`Session on ${session.chargerCode} completed.`, 'ok');
        this.load();
      },
      error: () => this.show('Could not complete the session.', 'error')
    });
  }

  protected low(vehicle: LatestVehicleTelemetry): boolean {
    return isLowBattery(vehicle);
  }

  protected age(vehicle: LatestVehicleTelemetry): string {
    return formatAge(vehicle.telemetryAgeSeconds);
  }

  protected label(value: string): string {
    const spaced = value.replace(/_/g, ' ').toLowerCase();
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
  }

  protected trackCharger(_index: number, charger: Charger): string {
    return charger.code;
  }

  protected trackSession(_index: number, session: ChargingSession): number {
    return session.id;
  }

  protected trackVehicle(_index: number, vehicle: LatestVehicleTelemetry): string {
    return vehicle.vehicleId;
  }

  private show(text: string, kind: 'ok' | 'error'): void {
    this.message.set(text);
    this.messageKind.set(kind);
    setTimeout(() => this.message.set(null), 6000);
  }
}
