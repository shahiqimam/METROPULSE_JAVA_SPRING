import { CommonModule } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { RouteStop, RouteSummary } from '../../../core/telemetry-api.service';
import { formatServiceTime } from '../vehicle-status';

/**
 * Scheduled routes and the stop pattern of the selected one.
 *
 * <p>Times come from stored stop times in service-day seconds, which is why they are formatted here
 * rather than being treated as clock times: a schedule can run past midnight.
 */
@Component({
  selector: 'app-route-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="panel" aria-label="Scheduled routes">
      <header class="panel__head">
        <h2>Schedule</h2>
        <span class="panel__meta">{{ routes().length }} route{{ routes().length === 1 ? '' : 's' }}</span>
      </header>

      <div class="routes">
        <button
          *ngFor="let route of routes(); trackBy: trackRoute"
          type="button"
          class="route"
          [class.route--active]="selectedRouteCode() === route.code"
          [attr.aria-pressed]="selectedRouteCode() === route.code"
          (click)="routeSelected.emit(route.code)"
        >
          <span class="route__code">{{ route.code }}</span>
          <span class="route__name">{{ route.longName }}</span>
          <span class="route__counts">{{ route.stopCount }} stops · {{ route.tripCount }} trips</span>
        </button>
      </div>

      <div class="pattern" *ngIf="stops().length > 0">
        <h3>Stop pattern</h3>
        <ol>
          <li *ngFor="let stop of stops(); trackBy: trackStop">
            <span class="pattern__seq">{{ stop.stopSequence }}</span>
            <span class="pattern__name">{{ stop.stopName }}</span>
            <span class="pattern__time">{{ time(stop.plannedArrivalSeconds) }}</span>
          </li>
        </ol>
      </div>
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

    .panel__meta {
      color: var(--ink-muted);
      font-size: 12px;
    }

    .routes {
      display: grid;
      gap: 1px;
      background: var(--hairline);
    }

    .route {
      display: grid;
      gap: 2px;
      padding: 12px 18px;
      border: 0;
      border-left: 3px solid transparent;
      background: var(--surface);
      text-align: left;
      cursor: pointer;
    }

    .route:hover {
      background: var(--surface-raised);
    }

    .route--active {
      border-left-color: var(--series-1);
      background: var(--surface-raised);
    }

    .route__code {
      font-family: var(--font-mono);
      font-size: 14px;
      font-weight: 700;
    }

    .route__name {
      color: var(--ink-secondary);
      font-size: 12px;
    }

    .route__counts {
      color: var(--ink-muted);
      font-size: 11px;
    }

    .pattern {
      padding: 14px 18px 16px;
      border-top: 1px solid var(--hairline);
      overflow-y: auto;
    }

    h3 {
      color: var(--ink-muted);
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }

    ol {
      display: grid;
      gap: 0;
      margin: 10px 0 0;
      padding: 0;
      list-style: none;
    }

    li {
      display: grid;
      grid-template-columns: 22px minmax(0, 1fr) auto;
      gap: 10px;
      align-items: center;
      padding: 7px 0;
      position: relative;
    }

    /* The connector makes the list read as an ordered line rather than separate rows. */
    li:not(:last-child)::before {
      content: '';
      position: absolute;
      left: 10px;
      top: 26px;
      bottom: -6px;
      width: 1px;
      background: var(--hairline-strong);
    }

    .pattern__seq {
      display: grid;
      place-items: center;
      width: 21px;
      height: 21px;
      border: 1px solid var(--hairline-strong);
      border-radius: 50%;
      background: var(--surface-sunken);
      color: var(--ink-secondary);
      font-size: 11px;
      font-weight: 700;
    }

    .pattern__name {
      font-size: 13px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .pattern__time {
      color: var(--ink-muted);
      font-size: 12px;
      font-variant-numeric: tabular-nums;
    }
  `]
})
export class RoutePanelComponent {
  readonly routes = input<RouteSummary[]>([]);
  readonly stops = input<RouteStop[]>([]);
  readonly selectedRouteCode = input<string | null>(null);

  readonly routeSelected = output<string>();

  protected time(seconds: number): string {
    return formatServiceTime(seconds);
  }

  protected trackRoute(_index: number, route: RouteSummary): string {
    return route.code;
  }

  protected trackStop(_index: number, stop: RouteStop): string {
    return stop.stopCode;
  }
}
