import { CommonModule } from '@angular/common';
import { Component, input, model, output } from '@angular/core';
import { FormsModule } from '@angular/forms';

/**
 * Connection settings, kept out of the operational view.
 *
 * <p>These are development conveniences: the backend uses HTTP Basic auth until the JWT slice lands,
 * and the credentials live in session storage. Nothing here is a substitute for real authentication,
 * which is enforced by the backend regardless of what this form says.
 */
@Component({
  selector: 'app-settings-drawer',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="scrim" *ngIf="open()" (click)="closed.emit()"></div>

    <aside class="drawer" [class.drawer--open]="open()" aria-label="Connection settings">
      <header>
        <h2>Connection</h2>
        <button type="button" class="icon-button" (click)="closed.emit()" aria-label="Close settings">✕</button>
      </header>

      <div class="body">
        <label>
          API base
          <input [ngModel]="apiBase()" (ngModelChange)="apiBase.set($event)" placeholder="/api/v1" />
        </label>

        <label>
          Username
          <input [ngModel]="username()" (ngModelChange)="username.set($event)" autocomplete="username" />
        </label>

        <label>
          Password
          <input
            type="password"
            [ngModel]="password()"
            (ngModelChange)="password.set($event)"
            autocomplete="current-password"
          />
        </label>

        <button type="button" class="primary" (click)="applied.emit()">Apply and reload</button>

        <p class="hint">
          Development credentials default to <code>operator</code> / <code>metropulse-dev-password</code>.
          Override them with <code>METROPULSE_OPERATOR_USERNAME</code> and
          <code>METROPULSE_OPERATOR_PASSWORD</code>.
        </p>

        <p class="error" *ngIf="error()">{{ error() }}</p>
      </div>
    </aside>
  `,
  styles: [`
    .scrim {
      position: fixed;
      inset: 0;
      z-index: 20;
      background: rgb(0 0 0 / 55%);
    }

    .drawer {
      position: fixed;
      top: 0;
      right: 0;
      bottom: 0;
      z-index: 21;
      width: min(360px, 90vw);
      display: flex;
      flex-direction: column;
      border-left: 1px solid var(--hairline-strong);
      background: var(--surface);
      transform: translateX(100%);
      transition: transform 180ms ease-out;
      box-shadow: -18px 0 40px rgb(0 0 0 / 40%);
    }

    .drawer--open {
      transform: translateX(0);
    }

    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 16px 18px;
      border-bottom: 1px solid var(--hairline);
      background: var(--surface-raised);
    }

    h2 {
      font-size: 13px;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    .icon-button {
      width: 30px;
      height: 30px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface);
      color: var(--ink-secondary);
      cursor: pointer;
    }

    .icon-button:hover {
      color: var(--ink-primary);
      border-color: var(--ink-muted);
    }

    .body {
      display: grid;
      align-content: start;
      gap: 14px;
      padding: 18px;
    }

    label {
      display: grid;
      gap: 6px;
      color: var(--ink-secondary);
      font-size: 12px;
      font-weight: 600;
    }

    input {
      height: 38px;
      padding: 0 11px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-sunken);
      font-family: var(--font-mono);
      font-size: 13px;
    }

    .primary {
      height: 38px;
      border: 0;
      border-radius: var(--radius-sm);
      background: var(--series-1);
      color: #fff;
      font-weight: 700;
      cursor: pointer;
    }

    .primary:hover {
      filter: brightness(1.08);
    }

    .hint {
      color: var(--ink-muted);
      font-size: 11px;
      line-height: 1.5;
    }

    code {
      font-family: var(--font-mono);
      font-size: 11px;
      color: var(--ink-secondary);
    }

    .error {
      padding: 10px 12px;
      border: 1px solid color-mix(in srgb, var(--status-critical) 45%, transparent);
      border-radius: var(--radius-sm);
      color: var(--status-critical);
      font-size: 12px;
    }
  `]
})
export class SettingsDrawerComponent {
  readonly open = input(false);
  readonly error = input<string | null>(null);

  readonly apiBase = model('');
  readonly username = model('');
  readonly password = model('');

  readonly closed = output<void>();
  readonly applied = output<void>();
}
