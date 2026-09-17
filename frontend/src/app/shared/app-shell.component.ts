import { CommonModule } from '@angular/common';
import { Component, inject, input } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService, UserRole } from '../core/auth/auth.service';
import { RealtimeService } from '../core/realtime/realtime.service';

/**
 * The frame every screen sits in: identity, navigation, session.
 *
 * <p>Extracted so the header cannot drift between pages — a control room where the connection
 * indicator means one thing on one screen and another elsewhere is worse than one with no indicator.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive],
  template: `
    <header class="topbar">
      <div class="brand">
        <span class="brand__mark" aria-hidden="true"></span>
        <span class="brand__text">
          <strong>MetroPulse</strong>
          <small>Network Operations</small>
        </span>
      </div>

      <nav aria-label="Sections">
        <a routerLink="/dashboard" routerLinkActive="active">Network</a>
        <a routerLink="/incidents" routerLinkActive="active">Incidents</a>
        <a routerLink="/ev" routerLinkActive="active">EV</a>
        <a routerLink="/analytics" routerLinkActive="active">Analytics</a>
        <a routerLink="/playback" routerLinkActive="active">Playback</a>
      </nav>

      <div class="topbar__right">
        <span class="channel" [attr.data-state]="realtimeState()" *ngIf="showChannel()">
          {{ realtimeState() === 'live' ? 'Streaming' : 'Polling' }}
        </span>
        <span class="operator" *ngIf="user() as operator">
          <strong>{{ operator.displayName }}</strong>
          <small>{{ roleLabel(operator.role) }}</small>
        </span>
        <button type="button" class="ghost" (click)="signOut()">Sign out</button>
      </div>
    </header>

    <ng-content />
  `,
  styles: [`
    .topbar {
      display: flex;
      align-items: center;
      gap: 20px;
      flex-wrap: wrap;
      padding: 12px 18px;
      border-bottom: 1px solid var(--hairline);
      background: var(--surface);
    }

    .brand {
      display: flex;
      align-items: center;
      gap: 11px;
    }

    .brand__mark {
      width: 12px;
      height: 26px;
      border-radius: 3px;
      background: linear-gradient(180deg, var(--series-1), var(--series-3));
    }

    .brand__text {
      display: grid;
    }

    .brand__text strong {
      font-size: 17px;
      font-weight: 700;
      letter-spacing: -0.01em;
    }

    .brand__text small {
      color: var(--ink-muted);
      font-size: 10px;
      letter-spacing: 0.1em;
      text-transform: uppercase;
    }

    nav {
      display: flex;
      gap: 2px;
      margin-right: auto;
    }

    nav a {
      padding: 7px 13px;
      border-radius: var(--radius-sm);
      color: var(--ink-secondary);
      font-size: 13px;
      font-weight: 600;
      text-decoration: none;
    }

    nav a:hover {
      color: var(--ink-primary);
      background: var(--surface-raised);
    }

    nav a.active {
      color: var(--ink-primary);
      background: var(--surface-raised);
      box-shadow: inset 0 -2px 0 var(--series-1);
    }

    .topbar__right {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .channel {
      padding: 3px 8px;
      border: 1px solid var(--hairline-strong);
      border-radius: 999px;
      color: var(--ink-muted);
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }

    .channel[data-state='live'] {
      border-color: color-mix(in srgb, var(--series-1) 55%, transparent);
      color: var(--series-1);
    }

    .operator {
      display: grid;
      text-align: right;
    }

    .operator strong {
      font-size: 13px;
    }

    .operator small {
      color: var(--ink-muted);
      font-size: 10px;
      letter-spacing: 0.06em;
      text-transform: uppercase;
    }

    .ghost {
      height: 32px;
      padding: 0 12px;
      border: 1px solid var(--hairline-strong);
      border-radius: var(--radius-sm);
      background: var(--surface);
      color: var(--ink-secondary);
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }

    .ghost:hover {
      color: var(--ink-primary);
      border-color: var(--ink-muted);
    }

    @media (max-width: 860px) {
      nav {
        order: 3;
        width: 100%;
        overflow-x: auto;
      }
    }
  `]
})
export class AppShellComponent {
  private readonly auth = inject(AuthService);
  private readonly realtime = inject(RealtimeService);
  private readonly router = inject(Router);

  /** Only the network view streams; the others read on demand. */
  readonly showChannel = input(false);

  protected readonly user = this.auth.user;
  protected readonly realtimeState = this.realtime.state;

  protected roleLabel(role: UserRole): string {
    return role.replace(/_/g, ' ').toLowerCase();
  }

  protected signOut(): void {
    this.realtime.disconnect();
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
