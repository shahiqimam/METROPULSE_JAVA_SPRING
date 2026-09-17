import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import {
  Incident,
  IncidentSeverity,
  IncidentStatus,
  IncidentType,
  TelemetryApiService
} from '../../core/telemetry-api.service';
import { AppShellComponent } from '../../shared/app-shell.component';

/** Which actions are offered from each state. Mirrors the backend's transition table. */
const ACTIONS: Record<IncidentStatus, { action: string; label: string }[]> = {
  OPEN: [
    { action: 'acknowledge', label: 'Acknowledge' },
    { action: 'mitigate', label: 'Start mitigation' },
    { action: 'resolve', label: 'Resolve' },
    { action: 'cancel', label: 'Cancel' }
  ],
  ACKNOWLEDGED: [
    { action: 'mitigate', label: 'Start mitigation' },
    { action: 'resolve', label: 'Resolve' },
    { action: 'cancel', label: 'Cancel' }
  ],
  MITIGATING: [{ action: 'resolve', label: 'Resolve' }],
  RESOLVED: [],
  CANCELLED: []
};

/**
 * Incident management.
 *
 * <p>The buttons offered are the transitions the backend allows from the incident's current state.
 * That is a convenience, not a control: the workflow is enforced server-side, and a hand-crafted
 * request for an illegal transition is refused with a 409 naming both states.
 */
@Component({
  selector: 'app-incidents',
  standalone: true,
  imports: [CommonModule, FormsModule, AppShellComponent],
  template: `
    <app-shell>
      <main class="page">
        <header class="page__head">
          <div>
            <h1>Incidents</h1>
            <p>{{ incidents().length }} shown · {{ liveCount() }} live</p>
          </div>
          <div class="head__actions">
            <label class="toggle">
              <input type="checkbox" [(ngModel)]="includeClosed" (ngModelChange)="load()" />
              Include closed
            </label>
            <button type="button" class="primary" *ngIf="canAct()" (click)="openForm.set(!openForm())">
              {{ openForm() ? 'Cancel' : 'Report incident' }}
            </button>
          </div>
        </header>

        <section class="form" *ngIf="openForm()">
          <h2>Report an incident</h2>
          <div class="form__grid">
            <label>
              Type
              <select [(ngModel)]="draftType">
                <option *ngFor="let type of types" [value]="type">{{ label(type) }}</option>
              </select>
            </label>
            <label>
              Severity
              <select [(ngModel)]="draftSeverity">
                <option *ngFor="let severity of severities" [value]="severity">{{ label(severity) }}</option>
              </select>
            </label>
            <label>
              Vehicle
              <input [(ngModel)]="draftVehicle" placeholder="BUS-042" />
            </label>
            <label>
              Route
              <input [(ngModel)]="draftRoute" placeholder="M42" />
            </label>
            <label class="form__wide">
              Title
              <input [(ngModel)]="draftTitle" placeholder="What happened" />
            </label>
            <label class="form__wide">
              Description
              <textarea [(ngModel)]="draftDescription" rows="2"></textarea>
            </label>
          </div>
          <p class="error" *ngIf="error() as message">{{ message }}</p>
          <button type="button" class="primary" [disabled]="!draftTitle.trim()" (click)="report()">
            Open incident
          </button>
        </section>

        <p class="empty" *ngIf="incidents().length === 0">No incidents.</p>

        <ul class="incidents">
          <li *ngFor="let incident of incidents(); trackBy: trackIncident" [attr.data-severity]="incident.severity">
            <div class="incident__head">
              <div>
                <span class="incident__number">{{ incident.incidentNumber }}</span>
                <span class="chip" [attr.data-status]="incident.status">{{ label(incident.status) }}</span>
              </div>
              <span class="incident__meta">
                {{ label(incident.type) }} · {{ incident.vehicleId ?? incident.routeCode ?? 'network' }}
              </span>
            </div>

            <h3>{{ incident.title }}</h3>
            <p class="incident__description" *ngIf="incident.description">{{ incident.description }}</p>

            <div class="incident__foot">
              <span class="incident__who">
                opened by {{ incident.openedBy }}
                <ng-container *ngIf="incident.assignedController">
                  · assigned to {{ incident.assignedController }}
                </ng-container>
                · {{ incident.startedAt | date: 'short' }}
              </span>
              <span class="incident__actions" *ngIf="canAct()">
                <button
                  type="button"
                  *ngFor="let option of actionsFor(incident)"
                  (click)="act(incident, option.action)"
                >
                  {{ option.label }}
                </button>
              </span>
            </div>

            <button type="button" class="link" (click)="toggle(incident.id)">
              {{ expanded() === incident.id ? 'Hide' : 'Show' }} timeline ({{ incident.timeline.length }})
            </button>

            <ol class="timeline" *ngIf="expanded() === incident.id">
              <li *ngFor="let entry of incident.timeline">
                <span class="timeline__time">{{ entry.recordedAt | date: 'shortTime' }}</span>
                <span class="timeline__what">
                  <ng-container *ngIf="entry.entryType === 'TRANSITION'">
                    {{ entry.fromStatus ? label(entry.fromStatus) + ' → ' : '' }}{{ label(entry.toStatus!) }}
                  </ng-container>
                  <ng-container *ngIf="entry.entryType === 'NOTE'">note</ng-container>
                </span>
                <span class="timeline__actor">{{ entry.actor }}</span>
                <span class="timeline__note" *ngIf="entry.note">{{ entry.note }}</span>
              </li>
            </ol>
          </li>
        </ul>
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
      flex-wrap: wrap;
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

    .head__actions {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .toggle {
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--ink-secondary);
      font-size: 12px;
    }

    .form {
      display: grid;
      gap: 12px;
      padding: 18px;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }

    .form h2 {
      font-size: 13px;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    .form__grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
    }

    .form__wide {
      grid-column: 1 / -1;
    }

    label {
      display: grid;
      gap: 5px;
      color: var(--ink-secondary);
      font-size: 12px;
      font-weight: 600;
    }

    input,
    select,
    textarea {
      min-height: 36px;
      padding: 7px 10px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-sunken);
      font-size: 13px;
      font-family: inherit;
    }

    .primary {
      justify-self: start;
      height: 34px;
      padding: 0 14px;
      border: 0;
      border-radius: var(--radius-sm);
      background: var(--series-1);
      color: #fff;
      font-size: 13px;
      font-weight: 700;
      cursor: pointer;
    }

    .primary:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .empty {
      padding: 40px;
      border: 1px dashed var(--hairline-strong);
      border-radius: var(--radius-lg);
      color: var(--ink-muted);
      text-align: center;
    }

    .incidents {
      display: grid;
      gap: 10px;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .incidents > li {
      padding: 14px 16px;
      border: 1px solid var(--hairline);
      border-left: 3px solid var(--ink-muted);
      border-radius: var(--radius-md);
      background: var(--surface);
    }

    li[data-severity='CRITICAL'] { border-left-color: var(--status-critical); }
    li[data-severity='MAJOR'] { border-left-color: var(--status-serious); }
    li[data-severity='MINOR'] { border-left-color: var(--status-warning); }

    .incident__head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }

    .incident__number {
      margin-right: 8px;
      font-family: var(--font-mono);
      font-size: 13px;
      font-weight: 700;
    }

    .chip {
      display: inline-block;
      padding: 2px 8px;
      border: 1px solid var(--hairline-strong);
      border-radius: 999px;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .chip[data-status='OPEN'] { color: var(--status-critical); border-color: color-mix(in srgb, var(--status-critical) 45%, transparent); }
    .chip[data-status='ACKNOWLEDGED'] { color: var(--status-warning); border-color: color-mix(in srgb, var(--status-warning) 45%, transparent); }
    .chip[data-status='MITIGATING'] { color: var(--series-1); border-color: color-mix(in srgb, var(--series-1) 45%, transparent); }
    .chip[data-status='RESOLVED'] { color: var(--status-good); border-color: color-mix(in srgb, var(--status-good) 45%, transparent); }

    .incident__meta,
    .incident__who {
      color: var(--ink-muted);
      font-size: 12px;
    }

    h3 {
      margin-top: 8px;
      font-size: 15px;
      font-weight: 600;
    }

    .incident__description {
      margin-top: 3px;
      color: var(--ink-secondary);
      font-size: 13px;
    }

    .incident__foot {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      margin-top: 10px;
    }

    .incident__actions {
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
    }

    .incident__actions button,
    .link {
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-raised);
      color: var(--ink-secondary);
      font-size: 11px;
      font-weight: 600;
      padding: 4px 9px;
      cursor: pointer;
    }

    .incident__actions button:hover,
    .link:hover {
      color: var(--ink-primary);
      border-color: var(--ink-muted);
    }

    .link {
      margin-top: 10px;
      background: none;
      border: 0;
      padding: 0;
      color: var(--ink-muted);
      text-decoration: underline;
    }

    .timeline {
      display: grid;
      gap: 6px;
      margin: 10px 0 0;
      padding: 10px 0 0;
      border-top: 1px solid var(--hairline);
      list-style: none;
      font-size: 12px;
    }

    .timeline li {
      display: grid;
      grid-template-columns: 70px 200px auto;
      gap: 8px;
    }

    .timeline__time,
    .timeline__actor {
      color: var(--ink-muted);
      font-variant-numeric: tabular-nums;
    }

    .timeline__note {
      grid-column: 2 / -1;
      color: var(--ink-secondary);
    }

    .error {
      color: var(--status-critical);
      font-size: 12px;
    }
  `]
})
export class IncidentsComponent {
  private readonly api = inject(TelemetryApiService);
  private readonly auth = inject(AuthService);

  protected readonly incidents = signal<Incident[]>([]);
  protected readonly expanded = signal<number | null>(null);
  protected readonly openForm = signal(false);
  protected readonly error = signal<string | null>(null);

  protected includeClosed = false;
  protected draftType: IncidentType = 'VEHICLE_BREAKDOWN';
  protected draftSeverity: IncidentSeverity = 'MAJOR';
  protected draftTitle = '';
  protected draftDescription = '';
  protected draftVehicle = '';
  protected draftRoute = '';

  protected readonly types: IncidentType[] = [
    'VEHICLE_BREAKDOWN',
    'ROAD_BLOCKAGE',
    'SERVICE_DISRUPTION',
    'PASSENGER_INCIDENT',
    'DEPOT_ISSUE',
    'OTHER'
  ];
  protected readonly severities: IncidentSeverity[] = ['CRITICAL', 'MAJOR', 'MINOR'];

  protected readonly canAct = computed(() => this.auth.canAct());
  protected readonly liveCount = computed(
    () => this.incidents().filter((incident) => !['RESOLVED', 'CANCELLED'].includes(incident.status)).length
  );

  constructor() {
    this.load();
  }

  protected load(): void {
    this.api.findIncidents(this.includeClosed).subscribe({
      next: (incidents) => this.incidents.set(incidents),
      error: () => this.incidents.set([])
    });
  }

  protected actionsFor(incident: Incident): { action: string; label: string }[] {
    return ACTIONS[incident.status] ?? [];
  }

  protected act(incident: Incident, action: string): void {
    this.api.incidentAction(incident.id, action).subscribe({
      next: () => this.load(),
      // A refusal means the screen was stale, so reloading shows the real state.
      error: () => this.load()
    });
  }

  protected report(): void {
    this.error.set(null);
    this.api
      .openIncident({
        type: this.draftType,
        severity: this.draftSeverity,
        title: this.draftTitle.trim(),
        description: this.draftDescription.trim() || null,
        vehicleId: this.draftVehicle.trim() || null,
        routeCode: this.draftRoute.trim() || null
      })
      .subscribe({
        next: () => {
          this.draftTitle = '';
          this.draftDescription = '';
          this.openForm.set(false);
          this.load();
        },
        error: (response) =>
          this.error.set(
            response.status === 403
              ? 'Your role cannot open incidents.'
              : 'Could not open the incident.'
          )
      });
  }

  protected toggle(id: number): void {
    this.expanded.update((current) => (current === id ? null : id));
  }

  protected label(value: string): string {
    const spaced = value.replace(/_/g, ' ').toLowerCase();
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
  }

  protected trackIncident(_index: number, incident: Incident): number {
    return incident.id;
  }
}
