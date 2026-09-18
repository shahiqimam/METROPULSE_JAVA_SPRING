import { CommonModule } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  LatestVehicleTelemetry,
  PlaybackFrame,
  PlaybackSession,
  RouteGeometryPoint,
  RouteStop,
  RouteSummary,
  TelemetryApiService
} from '../../core/telemetry-api.service';
import { AppShellComponent } from '../../shared/app-shell.component';
import { NetworkMapComponent } from '../dashboard/components/network-map.component';

/**
 * Historical replay.
 *
 * <p>Frames are grouped into moments — one position per vehicle per instant — and stepped through on
 * the same map the live view uses, so a replay is read exactly like the present.
 *
 * <p>Nothing here writes: the server treats playback as read-only, and this screen only asks for
 * frames. A replay cannot make the control centre believe a bus is somewhere it was an hour ago.
 */
@Component({
  selector: 'app-playback',
  standalone: true,
  imports: [CommonModule, FormsModule, AppShellComponent, NetworkMapComponent],
  template: `
    <app-shell>
      <main class="page">
        <header class="page__head">
          <div>
            <h1>Playback</h1>
            <p>Replays stored telemetry. Live state is never touched.</p>
          </div>
        </header>

        <section class="controls">
          <label>
            Route
            <select [(ngModel)]="routeCode">
              <option value="">All routes</option>
              <option *ngFor="let route of routes()" [value]="route.code">{{ route.code }}</option>
            </select>
          </label>
          <label>
            Vehicle
            <input [(ngModel)]="vehicleId" placeholder="All vehicles" />
          </label>
          <label>
            Window
            <select [(ngModel)]="windowMinutes">
              <option [value]="5">Last 5 minutes</option>
              <option [value]="15">Last 15 minutes</option>
              <option [value]="60">Last hour</option>
              <option [value]="240">Last 4 hours</option>
            </select>
          </label>
          <label>
            Speed
            <select [(ngModel)]="speed">
              <option [value]="1">1x</option>
              <option [value]="5">5x</option>
              <option [value]="10">10x</option>
              <option [value]="30">30x</option>
            </select>
          </label>
          <button type="button" class="primary" [disabled]="loading()" (click)="loadSession()">
            {{ loading() ? 'Loading…' : 'Load' }}
          </button>
        </section>

        <p class="banner" *ngIf="error() as message">{{ message }}</p>

        <section class="session" *ngIf="session() as data">
          <span><strong>{{ data.frameCount }}</strong> frames</span>
          <span>{{ data.from | date: 'short' }} → {{ data.to | date: 'shortTime' }}</span>
          <span>{{ moments().length }} moments loaded</span>
          <span *ngIf="data.frameCount > loadedFrames()" class="truncated">
            showing the first {{ loadedFrames() }}
          </span>
        </section>

        <app-network-map
          [routeCode]="routeCode || null"
          [geometry]="geometry()"
          [stops]="stops()"
          [vehicles]="currentVehicles()"
        />

        <section class="transport" *ngIf="moments().length > 0">
          <button type="button" (click)="togglePlay()">{{ playing() ? 'Pause' : 'Play' }}</button>
          <button type="button" (click)="step(-1)" [disabled]="index() === 0">Back</button>
          <button type="button" (click)="step(1)" [disabled]="index() >= moments().length - 1">Forward</button>

          <input
            type="range"
            min="0"
            [max]="moments().length - 1"
            [ngModel]="index()"
            (ngModelChange)="seek($event)"
            aria-label="Position in the replay"
          />

          <span class="clock">{{ currentTime() }}</span>
          <span class="position">{{ index() + 1 }} / {{ moments().length }}</span>
        </section>

        <p class="empty" *ngIf="session() && moments().length === 0">
          No telemetry was recorded in that window.
        </p>
      </main>
    </app-shell>
  `,
  styles: [`
    .page {
      width: min(1200px, 100%);
      margin: 0 auto;
      padding: 20px 18px 40px;
      display: grid;
      gap: 14px;
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

    .controls {
      display: flex;
      align-items: flex-end;
      gap: 12px;
      flex-wrap: wrap;
      padding: 14px 16px;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }

    label {
      display: grid;
      gap: 5px;
      color: var(--ink-secondary);
      font-size: 12px;
      font-weight: 600;
    }

    select,
    input[type='text'],
    input:not([type]) {
      height: 32px;
      min-width: 140px;
      padding: 0 8px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-sunken);
      font-size: 13px;
    }

    .primary {
      height: 32px;
      padding: 0 16px;
      border: 0;
      border-radius: var(--radius-sm);
      background: var(--series-1);
      color: #fff;
      font-size: 13px;
      font-weight: 700;
      cursor: pointer;
    }

    .primary:disabled {
      opacity: 0.6;
      cursor: progress;
    }

    .session {
      display: flex;
      gap: 18px;
      flex-wrap: wrap;
      padding: 10px 16px;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-md);
      background: var(--surface);
      color: var(--ink-secondary);
      font-size: 12px;
    }

    .truncated {
      color: var(--status-warning);
    }

    app-network-map {
      display: block;
      min-height: 420px;
    }

    .transport {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 16px;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }

    .transport button {
      height: 32px;
      min-width: 72px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-raised);
      color: var(--ink-secondary);
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }

    .transport button:hover:not(:disabled) {
      color: var(--ink-primary);
      border-color: var(--ink-muted);
    }

    .transport button:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    input[type='range'] {
      flex: 1;
      accent-color: var(--series-1);
    }

    .clock {
      font-family: var(--font-mono);
      font-size: 13px;
      font-variant-numeric: tabular-nums;
    }

    .position {
      color: var(--ink-muted);
      font-size: 12px;
      font-variant-numeric: tabular-nums;
    }

    .banner,
    .empty {
      padding: 12px 16px;
      border-radius: var(--radius-md);
      font-size: 13px;
      text-align: center;
    }

    .banner {
      border: 1px solid color-mix(in srgb, var(--status-critical) 45%, transparent);
      color: var(--status-critical);
    }

    .empty {
      border: 1px dashed var(--hairline-strong);
      color: var(--ink-muted);
    }
  `]
})
export class PlaybackComponent implements OnDestroy {
  private readonly api = inject(TelemetryApiService);

  /** Frames are fetched in one page; the session reports the true total if it is larger. */
  private static readonly PAGE_SIZE = 1000;

  protected routeCode = '';
  protected vehicleId = '';
  protected windowMinutes = 15;
  protected speed = 5;

  protected readonly routes = signal<RouteSummary[]>([]);
  protected readonly geometry = signal<RouteGeometryPoint[]>([]);
  protected readonly stops = signal<RouteStop[]>([]);
  protected readonly session = signal<PlaybackSession | null>(null);
  protected readonly moments = signal<PlaybackFrame[][]>([]);
  protected readonly index = signal(0);
  protected readonly playing = signal(false);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly loadedFrames = signal(0);

  private timer: number | undefined;

  /** The frames at the current moment, shaped like live state so the map can draw them. */
  protected readonly currentVehicles = computed<LatestVehicleTelemetry[]>(() => {
    const moment = this.moments()[this.index()] ?? [];
    return moment.map((frame) => ({
      vehicleId: frame.vehicleId,
      vehicleType: 'STANDARD_BUS',
      propulsionType: 'BATTERY_ELECTRIC',
      capacity: 80,
      status: 'ACTIVE',
      sourceEventId: '',
      recordedAt: frame.recordedAt,
      receivedAt: frame.recordedAt,
      latitude: frame.latitude,
      longitude: frame.longitude,
      speedKph: frame.speedKph,
      headingDegrees: frame.headingDegrees,
      occupancyEstimate: frame.occupancyEstimate,
      batteryPercent: frame.batteryPercent,
      routeCode: this.routeCode || null,
      routeProgress: null,
      routeDeviationMeters: null,
      // Stored frames carry position, not the schedule comparison that was made against them at the
      // time. Recomputing one now would compare an hour-old position against the timetable as it
      // stands today, which is a different measurement wearing the same name.
      tripCode: null,
      scheduleDeviationSeconds: null,
      nextStopName: null,
      dwellingAtStopName: null,
      dwellSeconds: null,
      // A replayed frame is historical by definition; age would be meaningless, so it reads as fresh
      // within the replay's own timeline rather than pretending to be live.
      telemetryAgeSeconds: 0,
      connectivityState: 'ONLINE'
    }));
  });

  protected readonly currentTime = computed(() => {
    const moment = this.moments()[this.index()];
    if (!moment || moment.length === 0) {
      return '—';
    }
    return new Date(moment[0].recordedAt).toLocaleTimeString();
  });

  constructor() {
    this.api.findRoutes().subscribe({
      next: (routes) => {
        this.routes.set(routes);
        if (routes.length > 0 && !this.routeCode) {
          this.routeCode = routes[0].code;
          this.loadRouteShape(routes[0].code);
        }
      }
    });
  }

  ngOnDestroy(): void {
    this.stop();
  }

  protected loadSession(): void {
    this.stop();
    this.loading.set(true);
    this.error.set(null);

    const to = new Date();
    const from = new Date(to.getTime() - Number(this.windowMinutes) * 60_000);

    if (this.routeCode) {
      this.loadRouteShape(this.routeCode);
    }

    this.api
      .createPlaybackSession(
        from.toISOString(),
        to.toISOString(),
        Number(this.speed),
        this.vehicleId.trim() || null,
        this.routeCode || null
      )
      .subscribe({
        next: (session) => {
          this.session.set(session);
          this.loadFrames(session);
        },
        error: (response) => {
          this.loading.set(false);
          this.error.set(
            response.status === 404
              ? 'That vehicle does not exist.'
              : response.error?.message ?? 'Could not create the replay.'
          );
        }
      });
  }

  private loadFrames(session: PlaybackSession): void {
    this.api.findPlaybackFrames(session.id, 0, PlaybackComponent.PAGE_SIZE).subscribe({
      next: (frames) => {
        this.loadedFrames.set(frames.length);
        this.moments.set(this.groupIntoMoments(frames));
        this.index.set(0);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not load frames.');
      }
    });
  }

  /**
   * Groups frames into moments.
   *
   * <p>Vehicles report independently, so their timestamps never line up exactly. Frames within a
   * second of each other are treated as one moment, which is what makes the replay show the fleet
   * rather than one vehicle at a time.
   */
  private groupIntoMoments(frames: PlaybackFrame[]): PlaybackFrame[][] {
    const moments: PlaybackFrame[][] = [];
    let current: PlaybackFrame[] = [];
    let currentSecond = -1;

    for (const frame of frames) {
      const second = Math.floor(new Date(frame.recordedAt).getTime() / 1000);
      if (currentSecond === -1 || second === currentSecond) {
        currentSecond = second;
        current.push(frame);
        continue;
      }

      moments.push(current);
      current = [frame];
      currentSecond = second;
    }

    if (current.length > 0) {
      moments.push(current);
    }
    return moments;
  }

  private loadRouteShape(routeCode: string): void {
    this.api.findRouteGeometry(routeCode).subscribe({ next: (geometry) => this.geometry.set(geometry) });
    this.api.findRouteStops(routeCode).subscribe({ next: (stops) => this.stops.set(stops) });
  }

  protected togglePlay(): void {
    if (this.playing()) {
      this.stop();
      return;
    }

    this.playing.set(true);
    // Moments are roughly a second apart, so the interval is one second divided by the speed.
    this.timer = window.setInterval(() => {
      if (this.index() >= this.moments().length - 1) {
        this.stop();
        return;
      }
      this.index.update((value) => value + 1);
    }, Math.max(1000 / Number(this.speed), 40));
  }

  protected step(direction: number): void {
    this.stop();
    const next = this.index() + direction;
    if (next >= 0 && next < this.moments().length) {
      this.index.set(next);
    }
  }

  protected seek(value: number): void {
    this.stop();
    this.index.set(Number(value));
  }

  private stop(): void {
    this.playing.set(false);
    if (this.timer !== undefined) {
      window.clearInterval(this.timer);
      this.timer = undefined;
    }
  }
}
