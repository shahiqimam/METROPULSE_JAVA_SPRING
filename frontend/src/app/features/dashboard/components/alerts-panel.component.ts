import { CommonModule } from '@angular/common';
import { Component, computed, input, output } from '@angular/core';
import { OperationalAlert } from '../../../core/telemetry-api.service';
import { formatAge } from '../vehicle-status';

const ALERT_LABELS: Record<string, string> = {
  TELEMETRY_OFFLINE: 'No telemetry',
  ROUTE_DEVIATION: 'Off route',
  BUNCHING: 'Bunching',
  EXCESSIVE_GAP: 'Excessive gap',
  LOW_BATTERY: 'Low battery',
  OVER_CAPACITY: 'Over capacity'
};

/**
 * The controller's attention list.
 *
 * <p>Alerts are the one thing on screen that ask for a decision, so this panel sits at the top and
 * carries the only actions in the dashboard. An alert that is recovering is shown as such rather
 * than disappearing: it has not closed yet, and a controller watching it should see that it is on
 * its way out rather than wonder where it went.
 */
@Component({
  selector: 'app-alerts-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="panel" aria-label="Alerts">
      <header class="panel__head">
        <h2>Alerts</h2>
        <span class="panel__meta">
          <ng-container *ngIf="alerts().length > 0; else quiet">
            {{ openCount() }} open · {{ acknowledgedCount() }} acknowledged
          </ng-container>
          <ng-template #quiet>nothing live</ng-template>
        </span>
      </header>

      <p class="panel__empty" *ngIf="alerts().length === 0">
        No live alerts.
      </p>

      <ul class="alerts">
        <li
          *ngFor="let alert of alerts(); trackBy: trackAlert"
          [attr.data-severity]="alert.severity"
          [class.alert--acknowledged]="alert.status === 'ACKNOWLEDGED'"
          [class.alert--recovering]="alert.recoveringSince"
        >
          <div class="alert__head">
            <span class="alert__type">{{ label(alert) }}</span>
            <span class="alert__vehicle">{{ alert.vehicleId ?? alert.routeCode }}</span>
          </div>

          <p class="alert__detail">{{ detail(alert) }}</p>

          <div class="alert__foot">
            <span class="alert__state">
              <ng-container *ngIf="alert.recoveringSince">Recovering · </ng-container>
              <ng-container *ngIf="alert.status === 'ACKNOWLEDGED'">
                Acknowledged by {{ alert.acknowledgedBy }} ·
              </ng-container>
              {{ age(alert) }}
            </span>
            <span class="alert__actions">
              <button
                type="button"
                *ngIf="alert.status === 'OPEN'"
                (click)="acknowledged.emit(alert.id)"
              >
                Acknowledge
              </button>
              <button type="button" class="secondary" (click)="closed.emit(alert.id)">Close</button>
            </span>
          </div>
        </li>
      </ul>
    </section>
  `,
  styles: [`
    .panel {
      display: flex;
      flex-direction: column;
      min-height: 0;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--surface);
      overflow: hidden;
    }

    .panel__head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 10px;
      padding: 14px 18px;
      border-bottom: 1px solid var(--hairline);
      background: var(--surface-raised);
    }

    h2 {
      font-size: 13px;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    .panel__meta {
      color: var(--ink-muted);
      font-size: 12px;
      white-space: nowrap;
    }

    .panel__empty {
      padding: 24px 18px;
      color: var(--ink-muted);
      text-align: center;
      font-size: 13px;
    }

    .alerts {
      margin: 0;
      padding: 0;
      list-style: none;
      overflow-y: auto;
    }

    .alerts li {
      padding: 12px 18px;
      border-left: 3px solid var(--ink-muted);
    }

    .alerts li + li {
      border-top: 1px solid var(--hairline);
    }

    li[data-severity='CRITICAL'] { border-left-color: var(--status-critical); }
    li[data-severity='MAJOR'] { border-left-color: var(--status-serious); }
    li[data-severity='MINOR'] { border-left-color: var(--status-warning); }

    /* An acknowledged alert recedes: it is still true, but somebody has it. */
    .alert--acknowledged {
      opacity: 0.72;
    }

    .alert--recovering .alert__type::after {
      content: ' ↓';
      color: var(--status-good);
    }

    .alert__head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 10px;
    }

    .alert__type {
      font-size: 13px;
      font-weight: 700;
    }

    li[data-severity='CRITICAL'] .alert__type { color: var(--status-critical); }
    li[data-severity='MAJOR'] .alert__type { color: var(--status-serious); }
    li[data-severity='MINOR'] .alert__type { color: var(--status-warning); }

    .alert__vehicle {
      font-family: var(--font-mono);
      font-size: 12px;
      color: var(--ink-secondary);
    }

    .alert__detail {
      margin-top: 3px;
      color: var(--ink-secondary);
      font-size: 12px;
    }

    .alert__foot {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      margin-top: 8px;
    }

    .alert__state {
      color: var(--ink-muted);
      font-size: 11px;
    }

    .alert__actions {
      display: flex;
      gap: 6px;
    }

    button {
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-raised);
      color: var(--ink-secondary);
      font-size: 11px;
      font-weight: 600;
      padding: 4px 9px;
      cursor: pointer;
      white-space: nowrap;
    }

    button:hover {
      color: var(--ink-primary);
      border-color: var(--ink-muted);
    }

    button.secondary {
      background: none;
    }
  `]
})
export class AlertsPanelComponent {
  readonly alerts = input<OperationalAlert[]>([]);

  readonly acknowledged = output<number>();
  readonly closed = output<number>();

  protected readonly openCount = computed(
    () => this.alerts().filter((alert) => alert.status === 'OPEN').length
  );
  protected readonly acknowledgedCount = computed(
    () => this.alerts().filter((alert) => alert.status === 'ACKNOWLEDGED').length
  );

  protected label(alert: OperationalAlert): string {
    return ALERT_LABELS[alert.type] ?? alert.type;
  }

  /** One line of context from the alert's own details, so the row says why it fired. */
  protected detail(alert: OperationalAlert): string {
    const details = alert.details ?? {};
    switch (alert.type) {
      case 'ROUTE_DEVIATION':
        return `${details['deviationMeters']} m from the route shape`;
      case 'TELEMETRY_OFFLINE':
        return `No telemetry for ${formatAge(Number(details['telemetryAgeSeconds'] ?? 0))}`;
      case 'LOW_BATTERY':
        return `Battery at ${details['batteryPercent']}%`;
      case 'OVER_CAPACITY':
        return `${details['occupancyEstimate']} on board, capacity ${details['capacity']}`;
      case 'BUNCHING':
      case 'EXCESSIVE_GAP':
        return `${details['followerVehicleId']} behind ${details['leaderVehicleId']} · ${details['ratioToTarget']}x target`;
      default:
        return '';
    }
  }

  protected age(alert: OperationalAlert): string {
    const openedAt = new Date(alert.openedAt).getTime();
    return `${formatAge((Date.now() - openedAt) / 1000)} ago`;
  }

  protected trackAlert(_index: number, alert: OperationalAlert): number {
    return alert.id;
  }
}
