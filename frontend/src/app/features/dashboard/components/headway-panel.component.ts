import { CommonModule } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { RouteHeadwaySnapshot } from '../../../core/telemetry-api.service';
import { formatAge } from '../vehicle-status';

/**
 * Spacing between consecutive vehicles on the selected route.
 *
 * <p>Pairs are drawn on a shared scale where the target headway sits at the midpoint, so "how far
 * from nominal" is read by position rather than by comparing numbers. The bunching and gap
 * thresholds are drawn as fixed marks on that scale, which is what makes a bar's position mean
 * something rather than just being long or short.
 */
@Component({
  selector: 'app-headway-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="panel" aria-label="Headway">
      <header class="panel__head">
        <h2>Headway</h2>
        <span class="panel__meta" *ngIf="snapshot() as data">
          target {{ data.targetHeadwaySeconds }}s · {{ data.vehiclesConsidered }} reporting
        </span>
      </header>

      <p class="panel__empty" *ngIf="!snapshot() || snapshot()!.pairs.length === 0">
        Not enough reporting vehicles to measure spacing.
      </p>

      <ng-container *ngIf="snapshot() as data">
        <ul class="conditions" *ngIf="data.conditions.length > 0">
          <li *ngFor="let condition of data.conditions; trackBy: trackCondition" [attr.data-state]="condition.confirmed ? 'confirmed' : 'watching'">
            <span class="conditions__type">
              {{ condition.type === 'BUNCHING' ? 'Bunching' : 'Excessive gap' }}
            </span>
            <span class="conditions__pair">
              {{ condition.followerVehicleId }} behind {{ condition.leaderVehicleId }}
            </span>
            <span class="conditions__state">
              {{ condition.confirmed ? 'Sustained' : 'Watching' }} · {{ age(condition.observedForSeconds) }}
            </span>
          </li>
        </ul>

        <div class="scale" *ngIf="data.pairs.length > 0">
          <div class="scale__track">
            <span class="scale__mark scale__mark--bunching" [style.left.%]="bunchingMark()"></span>
            <span class="scale__mark scale__mark--target" [style.left.%]="targetMark()"></span>
            <span class="scale__mark scale__mark--gap" [style.left.%]="gapMark()"></span>
          </div>
          <div class="scale__labels">
            <span [style.left.%]="bunchingMark()">bunched</span>
            <span [style.left.%]="targetMark()">target</span>
            <span [style.left.%]="gapMark()">gap</span>
          </div>
        </div>

        <ul class="pairs">
          <li *ngFor="let pair of data.pairs; trackBy: trackPair" [attr.data-class]="pair.classification">
            <div class="pairs__label">
              <span class="pairs__vehicles">{{ pair.followerVehicleId }} → {{ pair.leaderVehicleId }}</span>
              <span class="pairs__value">
                {{ pair.headwaySeconds === null ? 'held' : (pair.headwaySeconds | number: '1.0-0') + 's' }}
              </span>
            </div>
            <div class="pairs__track">
              <span
                class="pairs__mark"
                [style.left.%]="position(pair.ratioToTarget)"
                [attr.title]="pair.classification"
              ></span>
            </div>
          </li>
        </ul>
      </ng-container>
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
      padding: 26px 18px;
      color: var(--ink-muted);
      text-align: center;
      font-size: 13px;
    }

    .conditions {
      margin: 0;
      padding: 0;
      list-style: none;
      border-bottom: 1px solid var(--hairline);
    }

    .conditions li {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: 2px 10px;
      padding: 10px 18px;
      border-left: 3px solid var(--status-warning);
      font-size: 12px;
    }

    .conditions li[data-state='confirmed'] {
      border-left-color: var(--status-critical);
      background: color-mix(in srgb, var(--status-critical) 9%, transparent);
    }

    .conditions__type {
      font-weight: 700;
      color: var(--status-warning);
    }

    .conditions li[data-state='confirmed'] .conditions__type {
      color: var(--status-critical);
    }

    .conditions__pair {
      font-family: var(--font-mono);
      color: var(--ink-secondary);
    }

    .conditions__state {
      grid-column: 1 / -1;
      color: var(--ink-muted);
      font-size: 11px;
    }

    .scale {
      position: relative;
      padding: 14px 18px 0;
    }

    .scale__track {
      position: relative;
      height: 3px;
      border-radius: 999px;
      background: var(--surface-sunken);
    }

    .scale__mark {
      position: absolute;
      top: -3px;
      width: 1px;
      height: 9px;
      transform: translateX(-50%);
    }

    .scale__mark--bunching { background: var(--status-critical); }
    .scale__mark--target { background: var(--ink-muted); }
    .scale__mark--gap { background: var(--status-warning); }

    .scale__labels {
      position: relative;
      height: 14px;
      margin-top: 3px;
      color: var(--ink-muted);
      font-size: 10px;
    }

    .scale__labels span {
      position: absolute;
      transform: translateX(-50%);
      white-space: nowrap;
    }

    .pairs {
      margin: 0;
      padding: 6px 18px 16px;
      list-style: none;
      overflow-y: auto;
    }

    .pairs li {
      padding: 7px 0;
    }

    .pairs__label {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      margin-bottom: 5px;
    }

    .pairs__vehicles {
      font-family: var(--font-mono);
      font-size: 12px;
      color: var(--ink-secondary);
    }

    .pairs__value {
      font-size: 12px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }

    .pairs li[data-class='BUNCHING'] .pairs__value { color: var(--status-critical); }
    .pairs li[data-class='EXCESSIVE_GAP'] .pairs__value { color: var(--status-warning); }
    .pairs li[data-class='UNKNOWN'] .pairs__value { color: var(--ink-muted); }

    .pairs__track {
      position: relative;
      height: 6px;
      border-radius: 999px;
      background: var(--surface-sunken);
    }

    .pairs__mark {
      position: absolute;
      top: -2px;
      width: 10px;
      height: 10px;
      border: 2px solid var(--surface);
      border-radius: 50%;
      background: var(--series-1);
      transform: translateX(-50%);
      transition: left 600ms ease-out;
    }

    .pairs li[data-class='BUNCHING'] .pairs__mark { background: var(--status-critical); }
    .pairs li[data-class='EXCESSIVE_GAP'] .pairs__mark { background: var(--status-warning); }
    .pairs li[data-class='UNKNOWN'] .pairs__mark { background: var(--ink-muted); }
  `]
})
export class HeadwayPanelComponent {
  readonly snapshot = input<RouteHeadwaySnapshot | null>(null);

  /** The scale runs from 0 to 2.5x target, so both thresholds and some headroom are on screen. */
  private readonly SCALE_MAX = 2.5;

  protected readonly bunchingMark = computed(() => (0.4 / this.SCALE_MAX) * 100);
  protected readonly targetMark = computed(() => (1 / this.SCALE_MAX) * 100);
  protected readonly gapMark = computed(() => (1.8 / this.SCALE_MAX) * 100);

  protected position(ratio: number | null): number {
    if (ratio === null) {
      return 0;
    }
    return Math.min(100, Math.max(0, (ratio / this.SCALE_MAX) * 100));
  }

  protected age(seconds: number): string {
    return formatAge(seconds);
  }

  protected trackPair(_index: number, pair: { followerVehicleId: string }): string {
    return pair.followerVehicleId;
  }

  protected trackCondition(_index: number, condition: { fingerprint: string }): string {
    return condition.fingerprint;
  }
}
