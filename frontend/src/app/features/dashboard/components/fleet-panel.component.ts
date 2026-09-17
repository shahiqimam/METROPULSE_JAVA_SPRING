import { CommonModule } from '@angular/common';
import { Component, computed, input, output } from '@angular/core';
import { LatestVehicleTelemetry } from '../../../core/telemetry-api.service';
import { connectivityLabel, formatAge, formatCode, isLowBattery, isOffRoute, statusRole } from '../vehicle-status';

/**
 * The fleet list: one row per vehicle, worst state first.
 *
 * <p>Each row states its status in words next to the coloured dot, so the list stays readable
 * without relying on colour, and shows route progress as a bar against the same scale for every
 * vehicle, which is what makes relative spacing (and therefore bunching) visible at a glance.
 */
@Component({
  selector: 'app-fleet-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="panel" aria-label="Fleet">
      <header class="panel__head">
        <h2>Fleet</h2>
        <span class="panel__meta">{{ vehicles().length }} tracked</span>
      </header>

      <p class="panel__empty" *ngIf="vehicles().length === 0">
        No vehicles are reporting.
      </p>

      <ul class="fleet">
        <li
          *ngFor="let vehicle of ordered(); trackBy: trackVehicle"
          class="row"
          [class.row--selected]="selectedVehicleId() === vehicle.vehicleId"
          (mouseenter)="hovered.emit(vehicle.vehicleId)"
          (mouseleave)="hovered.emit(null)"
        >
          <button type="button" (click)="selected.emit(vehicle.vehicleId)">
            <div class="row__top">
              <span class="row__id">{{ vehicle.vehicleId }}</span>
              <span class="chip" [attr.data-role]="role(vehicle)">
                <span class="chip__dot"></span>{{ label(vehicle) }}
              </span>
            </div>

            <div class="row__meta">
              <span>{{ type(vehicle) }}</span>
              <span class="row__age">{{ age(vehicle) }} ago</span>
            </div>

            <div class="bar" [attr.aria-label]="'Route progress ' + ((vehicle.routeProgress ?? 0) * 100 | number: '1.0-0') + '%'">
              <div class="bar__fill" [style.width.%]="(vehicle.routeProgress ?? 0) * 100"></div>
            </div>

            <dl class="row__stats">
              <div>
                <dt>Progress</dt>
                <dd>{{ (vehicle.routeProgress ?? 0) * 100 | number: '1.0-0' }}%</dd>
              </div>
              <div>
                <dt>Speed</dt>
                <dd>{{ vehicle.speedKph | number: '1.0-0' }} <small>kph</small></dd>
              </div>
              <div>
                <dt>Load</dt>
                <dd>{{ vehicle.occupancyEstimate }}<small>/{{ vehicle.capacity }}</small></dd>
              </div>
              <div>
                <dt>Battery</dt>
                <dd [class.dd--alert]="lowBattery(vehicle)">
                  {{ vehicle.batteryPercent ?? '—' }}<small *ngIf="vehicle.batteryPercent !== null">%</small>
                </dd>
              </div>
              <div>
                <dt>Off shape</dt>
                <dd [class.dd--alert]="offRoute(vehicle)">
                  {{ vehicle.routeDeviationMeters | number: '1.0-0' }} <small>m</small>
                </dd>
              </div>
            </dl>
          </button>
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

    .panel__meta,
    .row__meta {
      color: var(--ink-muted);
      font-size: 12px;
    }

    .panel__empty {
      padding: 32px 18px;
      color: var(--ink-muted);
      text-align: center;
    }

    .fleet {
      flex: 1;
      margin: 0;
      padding: 0;
      list-style: none;
      overflow-y: auto;
    }

    .row + .row {
      border-top: 1px solid var(--hairline);
    }

    .row button {
      display: block;
      width: 100%;
      padding: 13px 18px;
      border: 0;
      border-left: 3px solid transparent;
      background: none;
      text-align: left;
      cursor: pointer;
    }

    .row:hover button {
      background: var(--surface-raised);
    }

    .row--selected button {
      border-left-color: var(--series-1);
      background: var(--surface-raised);
    }

    .row__top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
    }

    .row__id {
      font-family: var(--font-mono);
      font-size: 14px;
      font-weight: 700;
      letter-spacing: 0.02em;
    }

    .row__meta {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      margin-top: 2px;
    }

    .row__age {
      font-variant-numeric: tabular-nums;
    }

    .chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 3px 9px;
      border: 1px solid var(--hairline-strong);
      border-radius: 999px;
      font-size: 11px;
      font-weight: 600;
      white-space: nowrap;
    }

    .chip__dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
    }

    .chip[data-role='good'] { color: var(--status-good); border-color: color-mix(in srgb, var(--status-good) 45%, transparent); }
    .chip[data-role='good'] .chip__dot { background: var(--status-good); }
    .chip[data-role='warning'] { color: var(--status-warning); border-color: color-mix(in srgb, var(--status-warning) 45%, transparent); }
    .chip[data-role='warning'] .chip__dot { background: var(--status-warning); }
    .chip[data-role='serious'] { color: var(--status-serious); border-color: color-mix(in srgb, var(--status-serious) 45%, transparent); }
    .chip[data-role='serious'] .chip__dot { background: var(--status-serious); }
    .chip[data-role='critical'] { color: var(--status-critical); border-color: color-mix(in srgb, var(--status-critical) 45%, transparent); }
    .chip[data-role='critical'] .chip__dot { background: var(--status-critical); }

    .bar {
      height: 4px;
      margin: 10px 0 9px;
      border-radius: 999px;
      background: var(--surface-sunken);
      overflow: hidden;
    }

    .bar__fill {
      height: 100%;
      border-radius: 999px;
      background: var(--series-1);
      transition: width 600ms ease-out;
    }

    .row__stats {
      display: grid;
      grid-template-columns: repeat(5, minmax(0, 1fr));
      gap: 8px;
    }

    .row__stats dt {
      color: var(--ink-muted);
      font-size: 10px;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .row__stats dd {
      margin-top: 2px;
      font-size: 14px;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }

    .row__stats small {
      color: var(--ink-muted);
      font-size: 11px;
      font-weight: 500;
    }

    .dd--alert {
      color: var(--status-serious);
    }
  `]
})
export class FleetPanelComponent {
  readonly vehicles = input<LatestVehicleTelemetry[]>([]);
  readonly selectedVehicleId = input<string | null>(null);

  readonly selected = output<string>();
  readonly hovered = output<string | null>();

  /** Worst state first, then by position along the route, so attention lands where it is needed. */
  protected readonly ordered = computed(() => {
    const severity: Record<string, number> = { critical: 0, serious: 1, warning: 2, good: 3 };
    return [...this.vehicles()].sort((left, right) => {
      const bySeverity = severity[statusRole(left)] - severity[statusRole(right)];
      return bySeverity !== 0 ? bySeverity : left.vehicleId.localeCompare(right.vehicleId);
    });
  });

  protected role(vehicle: LatestVehicleTelemetry): string {
    return statusRole(vehicle);
  }

  protected label(vehicle: LatestVehicleTelemetry): string {
    return connectivityLabel(vehicle);
  }

  protected type(vehicle: LatestVehicleTelemetry): string {
    return `${formatCode(vehicle.vehicleType)} · ${formatCode(vehicle.propulsionType)}`;
  }

  protected age(vehicle: LatestVehicleTelemetry): string {
    return formatAge(vehicle.telemetryAgeSeconds);
  }

  protected offRoute(vehicle: LatestVehicleTelemetry): boolean {
    return isOffRoute(vehicle);
  }

  protected lowBattery(vehicle: LatestVehicleTelemetry): boolean {
    return isLowBattery(vehicle);
  }

  protected trackVehicle(_index: number, vehicle: LatestVehicleTelemetry): string {
    return vehicle.vehicleId;
  }
}
