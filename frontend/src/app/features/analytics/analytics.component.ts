import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  EvAnalytics,
  IncidentAnalytics,
  Punctuality,
  ServiceRegularity,
  TelemetryApiService
} from '../../core/telemetry-api.service';
import { AppShellComponent } from '../../shared/app-shell.component';

const ALERT_LABELS: Record<string, string> = {
  TELEMETRY_OFFLINE: 'No telemetry',
  ROUTE_DEVIATION: 'Off route',
  BUNCHING: 'Bunching',
  EXCESSIVE_GAP: 'Excessive gap',
  LOW_BATTERY: 'Low battery',
  OVER_CAPACITY: 'Over capacity',
  VEHICLE_LATE: 'Running late',
  VEHICLE_EARLY: 'Running early',
  LONG_DWELL: 'Long dwell'
};

/**
 * Operational metrics.
 *
 * <p>Bars rather than a charting library: with six categories and one measure, a bar whose length is
 * its share of the largest value says everything a chart would, and every number is printed beside it
 * so nothing has to be read off an axis.
 *
 * <p>Punctuality and regularity are shown as two sections rather than one score. A frequent service
 * can run perfectly evenly and be consistently late, or be punctual on average while bunching badly,
 * so blending them would produce a number that answered neither question.
 */
@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [CommonModule, FormsModule, AppShellComponent],
  template: `
    <app-shell>
      <main class="page">
        <header class="page__head">
          <div>
            <h1>Analytics</h1>
            <p>Measured over the last {{ windowHours }} hours, from stored history.</p>
          </div>
          <label class="window">
            Window
            <select [(ngModel)]="windowHours" (ngModelChange)="load()">
              <option [value]="1">1 hour</option>
              <option [value]="24">24 hours</option>
              <option [value]="168">7 days</option>
              <option [value]="720">30 days</option>
            </select>
          </label>
        </header>

        <section class="panel">
          <h2>Punctuality</h2>
          <p class="note">
            Whether the service ran to its timetable, from recorded stop arrivals: actual against
            planned. On time is 90 seconds early to 5 minutes late — asymmetric because a late bus
            still turns up, while an early one has gone.
          </p>

          <p class="empty" *ngIf="punctuality().length === 0">
            No stop arrivals recorded in this window.
          </p>

          <table *ngIf="punctuality().length > 0">
            <thead>
              <tr>
                <th>Route</th><th>On time</th><th>Calls</th><th>Late</th><th>Early</th>
                <th>Average</th><th>Worst late</th><th>Worst early</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let route of punctuality(); trackBy: trackPunctuality">
                <td class="mono">{{ route.routeCode }}</td>
                <td>
                  <span class="score" [class.warn]="route.onTimePercent < 80">
                    {{ route.onTimePercent | number: '1.0-1' }}%
                  </span>
                  <span class="bars__track bars__track--inline">
                    <span class="bars__fill" [style.width.%]="route.onTimePercent"></span>
                  </span>
                </td>
                <td>{{ route.measuredCalls }}</td>
                <td [class.warn]="route.late > 0">{{ route.late }}</td>
                <td [class.warn]="route.early > 0">{{ route.early }}</td>
                <td>{{ signed(route.averageDeviationSeconds) }}</td>
                <td>{{ worst(route.worstLateSeconds, 'late') }}</td>
                <td>{{ worst(route.worstEarlySeconds, 'early') }}</td>
              </tr>
            </tbody>
          </table>

          <p class="note">
            A call that was never detected is not counted, which understates how many calls were made
            rather than overstating how punctual they were.
          </p>
        </section>

        <section class="panel">
          <h2>Service regularity</h2>
          <p class="note">
            How evenly spaced the service ran — a different question from whether it ran on time.
          </p>

          <table>
            <thead>
              <tr>
                <th>Route</th><th>Target</th><th>Live conditions</th>
                <th>Bunching alerts</th><th>Gap alerts</th><th>Average duration</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let route of regularity(); trackBy: trackRoute">
                <td class="mono">{{ route.routeCode }}</td>
                <td>{{ route.targetHeadwaySeconds }}s</td>
                <td>
                  <span [class.warn]="route.conditionsNow > 0">{{ route.conditionsNow }}</span>
                  <small *ngIf="route.conditionsNow > 0">
                    ({{ route.bunchingNow }} bunched, {{ route.excessiveGapsNow }} gapped)
                  </small>
                </td>
                <td>{{ route.bunchingAlertsInWindow }}</td>
                <td>{{ route.excessiveGapAlertsInWindow }}</td>
                <td>{{ route.averageAlertSeconds | number: '1.0-0' }}s</td>
              </tr>
            </tbody>
          </table>
        </section>

        <div class="two-up">
          <section class="panel">
            <h2>Alerts by type</h2>
            <p class="empty" *ngIf="alertEntries().length === 0">No alerts in this window.</p>
            <ul class="bars">
              <li *ngFor="let entry of alertEntries()">
                <span class="bars__label">{{ entry.label }}</span>
                <span class="bars__track">
                  <span class="bars__fill" [style.width.%]="entry.share"></span>
                </span>
                <span class="bars__value">{{ entry.count }}</span>
              </li>
            </ul>
            <p class="note" *ngIf="alertEntries().length > 0">
              Distinct problems, not measurements: the engine deduplicates by fingerprint.
            </p>
          </section>

          <section class="panel" *ngIf="incidents() as data">
            <h2>Incidents</h2>
            <dl class="stats">
              <div><dt>Total</dt><dd>{{ data.total }}</dd></div>
              <div><dt>Live</dt><dd [class.warn]="data.live > 0">{{ data.live }}</dd></div>
              <div><dt>Resolved</dt><dd>{{ data.resolved }}</dd></div>
              <div><dt>Cancelled</dt><dd>{{ data.cancelled }}</dd></div>
              <div><dt>To acknowledge</dt><dd>{{ minutes(data.averageSecondsToAcknowledge) }}</dd></div>
              <div><dt>To resolve</dt><dd>{{ minutes(data.averageSecondsToResolve) }}</dd></div>
            </dl>
            <p class="note">Timings come from the incidents' own timestamps.</p>
          </section>
        </div>

        <section class="panel" *ngIf="ev() as data">
          <h2>EV operations</h2>
          <dl class="stats stats--wide">
            <div><dt>Chargers</dt><dd>{{ data.chargersTotal }}</dd></div>
            <div><dt>Available</dt><dd>{{ data.chargersAvailable }}</dd></div>
            <div><dt>Occupied</dt><dd>{{ data.chargersOccupied }}</dd></div>
            <div><dt>Out of service</dt><dd [class.warn]="data.chargersOutOfService > 0">{{ data.chargersOutOfService }}</dd></div>
            <div><dt>Sessions</dt><dd>{{ data.sessionsInWindow }}</dd></div>
            <div><dt>Average session</dt><dd>{{ minutes(data.averageSessionSeconds) }}</dd></div>
            <div><dt>Charge gained</dt><dd>{{ data.averageBatteryPercentGained | number: '1.0-1' }}%</dd></div>
            <div><dt>Fleet battery</dt><dd>{{ data.averageFleetBatteryPercent | number: '1.0-0' }}%</dd></div>
            <div><dt>Low battery</dt><dd [class.warn]="data.lowBatteryVehicles > 0">{{ data.lowBatteryVehicles }}</dd></div>
          </dl>
          <p class="note">
            Charger utilisation is the share occupied now, not averaged over the window: sessions are
            not sampled often enough for a time-weighted figure to mean anything yet.
          </p>
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

    .window {
      display: grid;
      gap: 5px;
      color: var(--ink-secondary);
      font-size: 12px;
      font-weight: 600;
    }

    select {
      height: 32px;
      padding: 0 8px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-sunken);
      font-size: 13px;
    }

    .panel {
      padding: 16px 18px;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }

    h2 {
      margin-bottom: 8px;
      font-size: 12px;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--ink-secondary);
    }

    .note {
      margin-top: 10px;
      color: var(--ink-muted);
      font-size: 11px;
      line-height: 1.55;
    }

    .score {
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }

    .bars__track--inline {
      display: inline-block;
      width: 56px;
      margin-left: 8px;
      vertical-align: middle;
    }

    .two-up {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: 16px;
    }

    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
      margin-top: 10px;
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
      padding: 8px;
      border-bottom: 1px solid var(--hairline);
      font-variant-numeric: tabular-nums;
    }

    td small {
      margin-left: 5px;
      color: var(--ink-muted);
    }

    .mono {
      font-family: var(--font-mono);
      font-weight: 600;
    }

    .bars {
      display: grid;
      gap: 8px;
      margin: 10px 0 0;
      padding: 0;
      list-style: none;
    }

    .bars li {
      display: grid;
      grid-template-columns: 120px minmax(0, 1fr) 40px;
      align-items: center;
      gap: 10px;
      font-size: 12px;
    }

    .bars__label {
      color: var(--ink-secondary);
    }

    .bars__track {
      height: 8px;
      border-radius: 3px;
      background: var(--surface-sunken);
      overflow: hidden;
    }

    .bars__fill {
      display: block;
      height: 100%;
      border-radius: 3px;
      background: var(--series-1);
    }

    .bars__value {
      text-align: right;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }

    .stats {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
      gap: 12px;
      margin-top: 10px;
    }

    .stats dt {
      color: var(--ink-muted);
      font-size: 10px;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .stats dd {
      margin-top: 3px;
      font-size: 20px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }

    .warn {
      color: var(--status-serious);
    }

    .empty {
      padding: 20px;
      color: var(--ink-muted);
      text-align: center;
      font-size: 13px;
    }
  `]
})
export class AnalyticsComponent {
  private readonly api = inject(TelemetryApiService);

  protected windowHours = 24;

  protected readonly regularity = signal<ServiceRegularity[]>([]);
  protected readonly punctuality = signal<Punctuality[]>([]);
  protected readonly alertCounts = signal<Record<string, number>>({});
  protected readonly incidents = signal<IncidentAnalytics | null>(null);
  protected readonly ev = signal<EvAnalytics | null>(null);

  /** Bars are shares of the largest value, so the longest bar is always full width. */
  protected readonly alertEntries = computed(() => {
    const counts = this.alertCounts();
    const entries = Object.entries(counts);
    if (entries.length === 0) {
      return [];
    }

    const largest = Math.max(...entries.map(([, count]) => count));
    return entries
      .sort(([, a], [, b]) => b - a)
      .map(([type, count]) => ({
        label: ALERT_LABELS[type] ?? type,
        count,
        share: largest === 0 ? 0 : (count / largest) * 100
      }));
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    const hours = Number(this.windowHours);
    this.api.findPunctuality(hours).subscribe({ next: (data) => this.punctuality.set(data) });
    this.api.findServiceRegularity(hours).subscribe({ next: (data) => this.regularity.set(data) });
    this.api.findAlertAnalytics(hours).subscribe({ next: (data) => this.alertCounts.set(data) });
    this.api.findIncidentAnalytics(hours).subscribe({ next: (data) => this.incidents.set(data) });
    this.api.findEvAnalytics(hours).subscribe({ next: (data) => this.ev.set(data) });
  }

  protected minutes(seconds: number): string {
    if (!seconds) {
      return '—';
    }
    if (seconds < 60) {
      return `${Math.round(seconds)}s`;
    }
    return `${Math.round(seconds / 60)}m`;
  }

  protected trackRoute(_index: number, route: ServiceRegularity): string {
    return route.routeCode;
  }

  protected trackPunctuality(_index: number, route: Punctuality): string {
    return route.routeCode;
  }

  /**
   * Seconds with their sign kept, because the sign is the whole meaning.
   *
   * <p>"+180s" and "-180s" are opposite operational problems, and a bare "180s" is unreadable.
   */
  /**
   * The worst call in one direction, or nothing when there was none in that direction.
   *
   * <p>These come from the largest and smallest deviation in the window, so on a route where nothing
   * ran early the smallest is still a late one. Printing it under "worst early" would report the best
   * call on the route as its worst.
   */
  protected worst(seconds: number, direction: 'late' | 'early'): string {
    const wrongWay = direction === 'late' ? seconds <= 0 : seconds >= 0;
    return wrongWay ? 'none' : this.signed(seconds);
  }

  protected signed(seconds: number): string {
    const rounded = Math.round(seconds);
    if (rounded === 0) {
      return 'on time';
    }
    return rounded > 0 ? `+${rounded}s late` : `${rounded}s early`;
  }
}
