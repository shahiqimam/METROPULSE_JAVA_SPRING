import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import {
  ScheduleChange,
  StagedScheduleImport,
  TelemetryApiService
} from '../../core/telemetry-api.service';
import { AppShellComponent } from '../../shared/app-shell.component';

/**
 * Schedule feeds waiting for a decision.
 *
 * <p>The screen exists because uploading a feed is not the same act as putting it into service. The
 * schedule is what every other number on this dashboard is measured against, so a planner needs to
 * read what a feed would change before it changes it — and afterwards, needs a record of who decided.
 *
 * <p>Each count is shown as added and updated rather than as a total. "Twelve routes" is the same
 * number whether the feed adds one to eleven or rewrites all of them, and those are not the same
 * decision.
 */
@Component({
  selector: 'app-schedule',
  standalone: true,
  imports: [CommonModule, AppShellComponent],
  template: `
    <app-shell>
      <main class="page">
        <header class="page__head">
          <div>
            <h1>Schedule</h1>
            <p>Feeds are reviewed before they replace the timetable in service.</p>
          </div>
          <label class="upload">
            <input type="file" multiple accept=".txt,.csv" (change)="stage($event)" [disabled]="busy()" />
            <span class="upload__button">{{ busy() ? 'Checking…' : 'Upload a feed' }}</span>
          </label>
        </header>

        <p class="banner banner--bad" *ngIf="error() as message">{{ message }}</p>

        <p class="empty" *ngIf="imports().length === 0 && !busy()">
          Nothing has been uploaded. A feed needs agency, routes, stops, calendar, trips and stop
          times; shapes are optional, but a new route cannot be created without one.
        </p>

        <section class="panel" *ngFor="let item of imports(); trackBy: trackImport">
          <header class="panel__head">
            <div>
              <h2>Import {{ item.id }}</h2>
              <p class="meta">
                Uploaded by {{ item.uploadedBy }} · {{ item.uploadedAt | date: 'medium' }}
                <ng-container *ngIf="item.activatedBy">
                  · activated by {{ item.activatedBy }}, {{ item.activatedAt | date: 'medium' }}
                </ng-container>
                <ng-container *ngIf="item.discardedBy">
                  · discarded by {{ item.discardedBy }}, {{ item.discardedAt | date: 'medium' }}
                </ng-container>
              </p>
            </div>
            <span class="status" [class]="'status--' + item.status.toLowerCase()">{{ item.status }}</span>
          </header>

          <table>
            <thead>
              <tr><th>Records</th><th>New</th><th>Written over</th></tr>
            </thead>
            <tbody>
              <tr *ngFor="let row of changeRows(item)">
                <td>{{ row.label }}</td>
                <td [class.added]="row.change.added > 0">{{ row.change.added }}</td>
                <td [class.updated]="row.change.updated > 0">{{ row.change.updated }}</td>
              </tr>
              <tr>
                <td>Stop times</td>
                <td colspan="2">{{ item.preview.stopTimes }} replaced wholesale per trip</td>
              </tr>
            </tbody>
          </table>

          <ul class="notes" *ngIf="item.preview.notes.length > 0">
            <li *ngFor="let note of item.preview.notes">{{ note }}</li>
          </ul>

          <details *ngIf="item.preview.unchangedInFeed.length > 0">
            <summary>{{ item.preview.unchangedInFeed.length }} existing record(s) this feed does not mention</summary>
            <p class="mono">{{ item.preview.unchangedInFeed.join(', ') }}</p>
          </details>

          <p class="result" *ngIf="item.result as result">
            Activated: {{ result.routes }} route(s), {{ result.stops }} stop(s), {{ result.trips }}
            trip(s), {{ result.stopTimes }} stop time(s) written.
          </p>

          <footer class="actions" *ngIf="item.status === 'STAGED' && canAdminister">
            <button class="primary" (click)="activate(item)" [disabled]="busy()">Put into service</button>
            <button (click)="discard(item)" [disabled]="busy()">Discard</button>
          </footer>
          <p class="note" *ngIf="item.status === 'STAGED' && !canAdminister">
            An administrator decides whether this goes into service.
          </p>
        </section>
      </main>
    </app-shell>
  `,
  styles: [`
    .page {
      width: min(1000px, 100%);
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

    .upload input {
      display: none;
    }

    .upload__button {
      display: inline-block;
      padding: 8px 14px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-sunken);
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
    }

    .panel {
      padding: 16px 18px;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--surface);
      display: grid;
      gap: 12px;
    }

    .panel__head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
    }

    h2 {
      font-size: 13px;
      font-weight: 700;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--ink-secondary);
    }

    .meta {
      margin-top: 3px;
      color: var(--ink-muted);
      font-size: 12px;
    }

    .status {
      padding: 3px 9px;
      border-radius: 999px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.04em;
    }

    .status--staged {
      background: var(--warning-soft, #fdf3d8);
      color: var(--warning-ink, #7a5b00);
    }

    .status--activated {
      background: var(--good-soft, #e0f2e5);
      color: var(--good-ink, #1f5c34);
    }

    .status--discarded {
      background: var(--surface-sunken);
      color: var(--ink-muted);
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
      font-variant-numeric: tabular-nums;
    }

    .added {
      font-weight: 700;
    }

    .updated {
      font-weight: 700;
      color: var(--warning-ink, #7a5b00);
    }

    .notes {
      display: grid;
      gap: 5px;
      padding-left: 16px;
      color: var(--ink-secondary);
      font-size: 12px;
      line-height: 1.5;
    }

    details {
      font-size: 12px;
      color: var(--ink-secondary);
    }

    summary {
      cursor: pointer;
    }

    .mono {
      margin-top: 6px;
      font-family: var(--font-mono, ui-monospace, monospace);
      font-size: 11px;
      color: var(--ink-muted);
      line-height: 1.6;
    }

    .result {
      font-size: 12px;
      color: var(--ink-secondary);
    }

    .actions {
      display: flex;
      gap: 8px;
    }

    button {
      height: 32px;
      padding: 0 14px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-sunken);
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
    }

    button.primary {
      border-color: transparent;
      background: var(--accent, #1f4fd8);
      color: #fff;
    }

    button:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    .banner {
      padding: 10px 12px;
      border-radius: var(--radius-sm);
      font-size: 13px;
    }

    .banner--bad {
      background: var(--critical-soft, #fdeaea);
      color: var(--critical-ink, #8a1c1c);
    }

    .empty, .note {
      color: var(--ink-muted);
      font-size: 12px;
      line-height: 1.6;
    }
  `]
})
export class ScheduleComponent {

  private readonly api = inject(TelemetryApiService);

  protected readonly imports = signal<StagedScheduleImport[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Only for deciding what to put on screen; the API refuses the request either way. */
  protected readonly canAdminister = inject(AuthService).canAdminister();

  constructor() {
    this.load();
  }

  protected load(): void {
    this.api.findScheduleImports().subscribe({
      next: (data) => this.imports.set(data),
      error: (err) => this.error.set(this.message(err))
    });
  }

  protected stage(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    if (files.length === 0) {
      return;
    }

    this.busy.set(true);
    this.error.set(null);
    this.api.stageScheduleImport(files).subscribe({
      next: () => {
        this.busy.set(false);
        input.value = '';
        this.load();
      },
      error: (err) => {
        this.busy.set(false);
        input.value = '';
        this.error.set(this.message(err));
      }
    });
  }

  protected activate(item: StagedScheduleImport): void {
    this.decide(this.api.activateScheduleImport(item.id));
  }

  protected discard(item: StagedScheduleImport): void {
    this.decide(this.api.discardScheduleImport(item.id));
  }

  protected changeRows(item: StagedScheduleImport): { label: string; change: ScheduleChange }[] {
    return [
      { label: 'Agencies', change: item.preview.agencies },
      { label: 'Routes', change: item.preview.routes },
      { label: 'Stops', change: item.preview.stops },
      { label: 'Calendars', change: item.preview.calendars },
      { label: 'Trips', change: item.preview.trips }
    ];
  }

  protected trackImport(_index: number, item: StagedScheduleImport): number {
    return item.id;
  }

  private decide(request: Observable<StagedScheduleImport>): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: () => {
        this.busy.set(false);
        this.load();
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(this.message(err));
      }
    });
  }

  /**
   * The server's own words where it has them.
   *
   * <p>A feed is rejected with a list of what is wrong with it, line by line. Replacing that with
   * "import failed" would throw away the only thing that helps a planner fix the file.
   */
  private message(error: unknown): string {
    const body = (error as { error?: { message?: string; details?: string[] } })?.error;
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message ?? 'The request failed.';
  }
}
