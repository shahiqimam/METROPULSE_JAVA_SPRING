import { CommonModule } from '@angular/common';
import { Component, computed, input, output } from '@angular/core';
import { LatestVehicleTelemetry, RouteGeometryPoint, RouteStop } from '../../../core/telemetry-api.service';
import { OFF_ROUTE_METERS, connectivityLabel, statusRole } from '../vehicle-status';

interface Projected {
  x: number;
  y: number;
}

interface MappedVehicle extends Projected {
  vehicle: LatestVehicleTelemetry;
  heading: number;
  role: string;
  offRoute: boolean;
}

interface MappedStop extends Projected {
  stop: RouteStop;
}

const VIEW_WIDTH = 1000;
const VIEW_HEIGHT = 620;
const PADDING = 64;

/**
 * Schematic map of one route and the vehicles running it.
 *
 * <p>The line is the route's stored PostGIS geometry, read from the backend rather than drawn by
 * hand: it is the same shape route progress and route deviation are measured against, so a vehicle
 * drawn off the line really is off the line.
 *
 * <p>Positions are projected with a local equirectangular transform (longitude scaled by the cosine
 * of the mid latitude). At city scale that is visually indistinguishable from a proper projection
 * and keeps the component free of a mapping dependency.
 */
@Component({
  selector: 'app-network-map',
  standalone: true,
  imports: [CommonModule],
  template: `
    <figure class="map">
      <figcaption class="map__head">
        <div>
          <h2>Network</h2>
          <p *ngIf="routeCode() as code">Route {{ code }} · {{ geometry().length }}-point shape · {{ stops().length }} stops</p>
        </div>
        <ul class="legend">
          <li><span class="swatch swatch--route"></span>Route shape</li>
          <li><span class="swatch swatch--good"></span>On route</li>
          <li><span class="swatch swatch--serious"></span>Off route</li>
          <li><span class="swatch swatch--critical"></span>No telemetry</li>
        </ul>
      </figcaption>

      <div class="map__canvas">
        <p class="map__empty" *ngIf="geometry().length < 2">Route shape unavailable.</p>

        <svg
          *ngIf="geometry().length > 1"
          [attr.viewBox]="'0 0 ' + VIEW_WIDTH + ' ' + VIEW_HEIGHT"
          role="img"
          [attr.aria-label]="'Map of route ' + routeCode() + ' with ' + vehicles().length + ' vehicles'"
        >
          <defs>
            <pattern id="grid" width="50" height="50" patternUnits="userSpaceOnUse">
              <path d="M 50 0 L 0 0 0 50" fill="none" stroke="var(--hairline)" stroke-width="1" opacity="0.5" />
            </pattern>
          </defs>

          <rect width="100%" height="100%" fill="url(#grid)" />

          <!-- Casing under the route line so the shape reads against the grid. -->
          <path [attr.d]="routePath()" class="route-casing" />
          <path [attr.d]="routePath()" class="route-line" />

          <g *ngFor="let mapped of mappedStops(); trackBy: trackStop" class="stop">
            <circle [attr.cx]="mapped.x" [attr.cy]="mapped.y" r="7" class="stop__ring" />
            <circle [attr.cx]="mapped.x" [attr.cy]="mapped.y" r="3" class="stop__core" />
            <text [attr.x]="mapped.x" [attr.y]="mapped.y - 16" class="stop__label">{{ mapped.stop.stopName }}</text>
          </g>

          <g
            *ngFor="let mapped of mappedVehicles(); trackBy: trackVehicle"
            class="vehicle"
            [class.vehicle--selected]="selectedVehicleId() === mapped.vehicle.vehicleId"
            (mouseenter)="hovered.emit(mapped.vehicle.vehicleId)"
            (mouseleave)="hovered.emit(null)"
            (click)="selected.emit(mapped.vehicle.vehicleId)"
            tabindex="0"
            role="button"
            [attr.aria-label]="mapped.vehicle.vehicleId + ', ' + label(mapped.vehicle)"
          >
            <!-- An off-route vehicle gets a halo as well as a colour, so the state is not colour-alone. -->
            <circle
              *ngIf="mapped.offRoute"
              [attr.cx]="mapped.x"
              [attr.cy]="mapped.y"
              r="22"
              class="vehicle__halo"
            />
            <circle
              *ngIf="selectedVehicleId() === mapped.vehicle.vehicleId"
              [attr.cx]="mapped.x"
              [attr.cy]="mapped.y"
              r="18"
              class="vehicle__selection"
            />
            <g [attr.transform]="'translate(' + mapped.x + ',' + mapped.y + ') rotate(' + mapped.heading + ')'">
              <path d="M 0 -11 L 8 9 L 0 4 L -8 9 Z" [attr.class]="'vehicle__mark vehicle__mark--' + mapped.role" />
            </g>
            <text [attr.x]="mapped.x + 14" [attr.y]="mapped.y + 5" class="vehicle__label">
              {{ mapped.vehicle.vehicleId }}
            </text>
          </g>
        </svg>

        <div class="tooltip" *ngIf="hoveredVehicle() as hovered">
          <strong>{{ hovered.vehicle.vehicleId }}</strong>
          <dl>
            <div><dt>State</dt><dd>{{ label(hovered.vehicle) }}</dd></div>
            <div><dt>Speed</dt><dd>{{ hovered.vehicle.speedKph | number: '1.0-1' }} kph</dd></div>
            <div><dt>Progress</dt><dd>{{ (hovered.vehicle.routeProgress ?? 0) * 100 | number: '1.0-0' }}%</dd></div>
            <div><dt>Off shape</dt><dd>{{ hovered.vehicle.routeDeviationMeters | number: '1.0-0' }} m</dd></div>
          </dl>
        </div>
      </div>
    </figure>
  `,
  styles: [`
    .map {
      display: flex;
      flex-direction: column;
      margin: 0;
      min-height: 0;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--surface);
      overflow: hidden;
    }

    .map__head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
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

    .map__head p {
      margin-top: 3px;
      color: var(--ink-muted);
      font-size: 12px;
    }

    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: 6px 14px;
      margin: 0;
      padding: 0;
      list-style: none;
      color: var(--ink-secondary);
      font-size: 11px;
    }

    .legend li {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .swatch {
      width: 10px;
      height: 10px;
      border-radius: 3px;
    }

    .swatch--route { background: var(--series-1); }
    .swatch--good { background: var(--status-good); }
    .swatch--serious { background: var(--status-serious); }
    .swatch--critical { background: var(--status-critical); }

    .map__canvas {
      position: relative;
      flex: 1;
      min-height: 320px;
      background: var(--surface-sunken);
    }

    svg {
      display: block;
      width: 100%;
      height: 100%;
    }

    .map__empty {
      display: grid;
      place-items: center;
      height: 100%;
      color: var(--ink-muted);
    }

    .route-casing {
      fill: none;
      stroke: var(--surface);
      stroke-width: 10;
      stroke-linecap: round;
      stroke-linejoin: round;
    }

    .route-line {
      fill: none;
      stroke: var(--series-1);
      stroke-width: 2.5;
      stroke-linecap: round;
      stroke-linejoin: round;
    }

    .stop__ring {
      fill: var(--surface-sunken);
      stroke: var(--ink-muted);
      stroke-width: 1.5;
    }

    .stop__core { fill: var(--ink-secondary); }

    .stop__label {
      fill: var(--ink-muted);
      font-size: 11px;
      font-family: var(--font-ui);
      text-anchor: middle;
    }

    .vehicle {
      cursor: pointer;
    }

    .vehicle__mark {
      stroke: var(--surface-sunken);
      stroke-width: 2;
      stroke-linejoin: round;
    }

    .vehicle__mark--good { fill: var(--status-good); }
    .vehicle__mark--warning { fill: var(--status-warning); }
    .vehicle__mark--serious { fill: var(--status-serious); }
    .vehicle__mark--critical { fill: var(--status-critical); }

    .vehicle__halo {
      fill: none;
      stroke: var(--status-serious);
      stroke-width: 1.5;
      stroke-dasharray: 3 4;
      opacity: 0.85;
    }

    .vehicle__selection {
      fill: none;
      stroke: var(--ink-primary);
      stroke-width: 1.5;
      opacity: 0.6;
    }

    .vehicle__label {
      fill: var(--ink-primary);
      font-size: 12px;
      font-weight: 600;
      font-family: var(--font-mono);
      paint-order: stroke;
      stroke: var(--surface-sunken);
      stroke-width: 3;
    }

    .vehicle--selected .vehicle__label { fill: #fff; }

    .tooltip {
      position: absolute;
      top: 14px;
      right: 14px;
      min-width: 190px;
      padding: 12px 14px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-md);
      background: var(--surface-raised);
      box-shadow: 0 8px 24px rgb(0 0 0 / 45%);
      pointer-events: none;
    }

    .tooltip strong {
      display: block;
      margin-bottom: 8px;
      font-family: var(--font-mono);
      font-size: 13px;
    }

    .tooltip dl {
      display: grid;
      gap: 5px;
      font-size: 12px;
    }

    .tooltip dl > div {
      display: flex;
      justify-content: space-between;
      gap: 12px;
    }

    .tooltip dt { color: var(--ink-muted); }
    .tooltip dd { font-variant-numeric: tabular-nums; }
  `]
})
export class NetworkMapComponent {
  readonly routeCode = input<string | null>(null);
  readonly geometry = input<RouteGeometryPoint[]>([]);
  readonly stops = input<RouteStop[]>([]);
  readonly vehicles = input<LatestVehicleTelemetry[]>([]);
  readonly selectedVehicleId = input<string | null>(null);
  readonly hoveredVehicleId = input<string | null>(null);

  readonly selected = output<string>();
  readonly hovered = output<string | null>();

  protected readonly VIEW_WIDTH = VIEW_WIDTH;
  protected readonly VIEW_HEIGHT = VIEW_HEIGHT;

  /** Projection derived from everything that has to fit on screen, recomputed when any of it moves. */
  private readonly transform = computed(() => {
    const points = [
      ...this.geometry().map((point) => ({ lat: point.latitude, lon: point.longitude })),
      ...this.stops().map((stop) => ({ lat: stop.latitude, lon: stop.longitude })),
      ...this.vehicles().map((vehicle) => ({ lat: vehicle.latitude, lon: vehicle.longitude }))
    ];

    if (points.length === 0) {
      return null;
    }

    const latitudes = points.map((point) => point.lat);
    const longitudes = points.map((point) => point.lon);
    const minLat = Math.min(...latitudes);
    const maxLat = Math.max(...latitudes);
    const minLon = Math.min(...longitudes);
    const maxLon = Math.max(...longitudes);
    const midLat = (minLat + maxLat) / 2;

    // Longitude degrees shrink toward the poles; scaling by cos(lat) keeps the shape's aspect honest.
    const lonScale = Math.cos((midLat * Math.PI) / 180);
    const spanX = Math.max((maxLon - minLon) * lonScale, 1e-6);
    const spanY = Math.max(maxLat - minLat, 1e-6);
    const scale = Math.min((VIEW_WIDTH - PADDING * 2) / spanX, (VIEW_HEIGHT - PADDING * 2) / spanY);

    const offsetX = (VIEW_WIDTH - spanX * scale) / 2;
    const offsetY = (VIEW_HEIGHT - spanY * scale) / 2;

    return { minLat, maxLat, minLon, lonScale, scale, offsetX, offsetY };
  });

  protected readonly routePath = computed(() => {
    const points = this.geometry();
    if (points.length < 2) {
      return '';
    }
    return points
      .map((point, index) => {
        const { x, y } = this.project(point.latitude, point.longitude);
        return `${index === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`;
      })
      .join(' ');
  });

  protected readonly mappedStops = computed<MappedStop[]>(() =>
    this.stops().map((stop) => ({ ...this.project(stop.latitude, stop.longitude), stop }))
  );

  protected readonly mappedVehicles = computed<MappedVehicle[]>(() =>
    this.vehicles().map((vehicle) => ({
      ...this.project(vehicle.latitude, vehicle.longitude),
      vehicle,
      heading: vehicle.headingDegrees,
      role: statusRole(vehicle),
      offRoute: (vehicle.routeDeviationMeters ?? 0) > OFF_ROUTE_METERS
    }))
  );

  protected readonly hoveredVehicle = computed<MappedVehicle | null>(() => {
    const id = this.hoveredVehicleId() ?? this.selectedVehicleId();
    return this.mappedVehicles().find((mapped) => mapped.vehicle.vehicleId === id) ?? null;
  });

  protected label(vehicle: LatestVehicleTelemetry): string {
    return connectivityLabel(vehicle);
  }

  protected trackVehicle(_index: number, mapped: MappedVehicle): string {
    return mapped.vehicle.vehicleId;
  }

  protected trackStop(_index: number, mapped: MappedStop): string {
    return mapped.stop.stopCode;
  }

  private project(latitude: number, longitude: number): Projected {
    const transform = this.transform();
    if (!transform) {
      return { x: VIEW_WIDTH / 2, y: VIEW_HEIGHT / 2 };
    }

    const { minLat, maxLat, minLon, lonScale, scale, offsetX, offsetY } = transform;
    return {
      x: offsetX + (longitude - minLon) * lonScale * scale,
      y: offsetY + (maxLat - latitude) * scale
    };
  }
}
