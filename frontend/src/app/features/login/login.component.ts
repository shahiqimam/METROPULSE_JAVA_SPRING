import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/** Sign-in for the control centre. */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <main class="page">
      <section class="card">
        <header>
          <span class="brand__mark" aria-hidden="true"></span>
          <div>
            <h1>MetroPulse</h1>
            <p>Network Operations</p>
          </div>
        </header>

        <form (ngSubmit)="submit()">
          <label>
            Email
            <input
              name="email"
              type="email"
              autocomplete="username"
              [(ngModel)]="email"
              [disabled]="submitting()"
              required
            />
          </label>

          <label>
            Password
            <input
              name="password"
              type="password"
              autocomplete="current-password"
              [(ngModel)]="password"
              [disabled]="submitting()"
              required
            />
          </label>

          <p class="error" *ngIf="error() as message" role="alert">{{ message }}</p>

          <button type="submit" [disabled]="submitting()">
            {{ submitting() ? 'Signing in…' : 'Sign in' }}
          </button>
        </form>

        <footer>
          <p class="hint">Development operators, one per role:</p>
          <ul>
            <li *ngFor="let operator of operators" (click)="fill(operator.email)">
              <code>{{ operator.email }}</code>
              <span>{{ operator.role }}</span>
            </li>
          </ul>
          <p class="hint">
            Passwords follow the pattern <code>&lt;role&gt;-dev-password</code>, for example
            <code>controller-dev-password</code>. Synthetic demo credentials only.
          </p>
        </footer>
      </section>
    </main>
  `,
  styles: [`
    .page {
      display: grid;
      place-items: center;
      min-height: 100vh;
      padding: 24px;
      background: var(--plane);
    }

    .card {
      width: min(420px, 100%);
      border: 1px solid var(--hairline);
      border-radius: var(--radius-lg);
      background: var(--surface);
      overflow: hidden;
    }

    header {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 22px 24px;
      border-bottom: 1px solid var(--hairline);
      background: var(--surface-raised);
    }

    .brand__mark {
      width: 12px;
      height: 32px;
      border-radius: 3px;
      background: linear-gradient(180deg, var(--series-1), var(--series-3));
    }

    h1 {
      font-size: 20px;
      font-weight: 700;
    }

    header p {
      color: var(--ink-muted);
      font-size: 11px;
      letter-spacing: 0.1em;
      text-transform: uppercase;
    }

    form {
      display: grid;
      gap: 14px;
      padding: 24px;
    }

    label {
      display: grid;
      gap: 6px;
      color: var(--ink-secondary);
      font-size: 12px;
      font-weight: 600;
    }

    input {
      height: 40px;
      padding: 0 12px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface-sunken);
      font-size: 14px;
    }

    button {
      height: 42px;
      border: 0;
      border-radius: var(--radius-sm);
      background: var(--series-1);
      color: #fff;
      font-size: 14px;
      font-weight: 700;
      cursor: pointer;
    }

    button:hover:not(:disabled) {
      filter: brightness(1.08);
    }

    button:disabled {
      cursor: progress;
      opacity: 0.7;
    }

    .error {
      padding: 10px 12px;
      border: 1px solid color-mix(in srgb, var(--status-critical) 45%, transparent);
      border-radius: var(--radius-sm);
      color: var(--status-critical);
      font-size: 13px;
    }

    footer {
      padding: 16px 24px 22px;
      border-top: 1px solid var(--hairline);
      background: var(--surface-sunken);
    }

    .hint {
      color: var(--ink-muted);
      font-size: 11px;
      line-height: 1.6;
    }

    ul {
      display: grid;
      gap: 4px;
      margin: 10px 0;
      padding: 0;
      list-style: none;
    }

    li {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      padding: 5px 8px;
      border: 1px solid var(--hairline);
      border-radius: var(--radius-sm);
      cursor: pointer;
    }

    li:hover {
      border-color: var(--hairline-strong);
    }

    code {
      font-family: var(--font-mono);
      font-size: 11px;
      color: var(--ink-secondary);
    }

    li span {
      color: var(--ink-muted);
      font-size: 10px;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }
  `]
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected email = 'controller@metropulse.test';
  protected password = '';

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly operators = [
    { email: 'admin@metropulse.test', role: 'Admin' },
    { email: 'controller@metropulse.test', role: 'Controller' },
    { email: 'supervisor@metropulse.test', role: 'Fleet supervisor' },
    { email: 'planner@metropulse.test', role: 'Planner' },
    { email: 'viewer@metropulse.test', role: 'Viewer' }
  ];

  protected fill(email: string): void {
    this.email = email;
    this.password = `${email.split('@')[0]}-dev-password`;
  }

  protected submit(): void {
    if (this.submitting()) {
      return;
    }

    this.submitting.set(true);
    this.error.set(null);

    this.auth.login(this.email, this.password).subscribe({
      next: () => {
        this.submitting.set(false);
        this.router.navigate(['/dashboard']);
      },
      error: (response) => {
        this.submitting.set(false);
        this.error.set(
          response.status === 401
            ? 'Invalid email or password.'
            : 'Cannot reach the backend. Check that the stack is running.'
        );
      }
    });
  }
}
