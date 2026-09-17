import { CommonModule } from '@angular/common';
import { Component, input } from '@angular/core';

export interface Metric {
  label: string;
  value: string;
  detail?: string;
  /** Set when the value itself is a warning, so the tile is read as a state rather than a number. */
  role?: 'serious' | 'critical' | null;
}

/**
 * The headline counters.
 *
 * <p>These are stat tiles, not charts: each answers one question with a single number, and a chart
 * would add ink without adding meaning. A tile only takes a status colour when its value is
 * non-zero, so a quiet network stays visually quiet.
 */
@Component({
  selector: 'app-metric-bar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="tiles" aria-label="Network summary">
      <article class="tile" *ngFor="let metric of metrics(); trackBy: trackMetric" [attr.data-role]="metric.role">
        <h3>{{ metric.label }}</h3>
        <p class="tile__value">{{ metric.value }}</p>
        <p class="tile__detail" *ngIf="metric.detail">{{ metric.detail }}</p>
      </article>
    </section>
  `,
  styles: [`
    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(148px, 1fr));
      gap: 1px;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--hairline);
      overflow: hidden;
    }

    .tile {
      padding: 13px 16px 14px;
      background: var(--surface);
    }

    h3 {
      color: var(--ink-muted);
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }

    .tile__value {
      margin-top: 6px;
      font-size: 26px;
      font-weight: 700;
      line-height: 1.1;
      letter-spacing: -0.01em;
    }

    .tile__detail {
      margin-top: 3px;
      color: var(--ink-muted);
      font-size: 11px;
    }

    .tile[data-role='serious'] {
      box-shadow: inset 3px 0 0 var(--status-serious);
    }

    .tile[data-role='serious'] .tile__value {
      color: var(--status-serious);
    }

    .tile[data-role='critical'] {
      box-shadow: inset 3px 0 0 var(--status-critical);
    }

    .tile[data-role='critical'] .tile__value {
      color: var(--status-critical);
    }
  `]
})
export class MetricBarComponent {
  readonly metrics = input<Metric[]>([]);

  protected trackMetric(_index: number, metric: Metric): string {
    return metric.label;
  }
}
