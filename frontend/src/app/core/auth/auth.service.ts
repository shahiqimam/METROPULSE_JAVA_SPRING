import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

export type UserRole = 'ADMIN' | 'CONTROLLER' | 'FLEET_SUPERVISOR' | 'PLANNER' | 'VIEWER';

export interface AuthenticatedUser {
  id: number;
  email: string;
  displayName: string;
  role: UserRole;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  user: AuthenticatedUser;
}

const ACCESS_TOKEN_KEY = 'metropulse.accessToken';
const REFRESH_TOKEN_KEY = 'metropulse.refreshToken';
const USER_KEY = 'metropulse.user';

/**
 * Holds the session.
 *
 * <p>Tokens live in sessionStorage rather than localStorage, so closing the tab ends the session.
 * Neither is safe against a script running on the page: the real protection is that the access token
 * is short-lived and the refresh token can be revoked. This is a synthetic demo, and the trade is
 * documented rather than hidden.
 *
 * <p>The role is read from the stored user only to decide what to show. It is never what makes an
 * action allowed - the backend decides that, and hiding a button is not authorisation.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  readonly apiBase = '/api/v1';

  private readonly currentUser = signal<AuthenticatedUser | null>(readUser());
  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUser() !== null);

  login(email: string, password: string): Observable<AuthTokens> {
    return this.http
      .post<AuthTokens>(`${this.apiBase}/auth/login`, { email, password })
      .pipe(tap((tokens) => this.store(tokens)));
  }

  refresh(): Observable<AuthTokens> {
    return this.http
      .post<AuthTokens>(`${this.apiBase}/auth/refresh`, { refreshToken: this.refreshToken() })
      .pipe(tap((tokens) => this.store(tokens)));
  }

  logout(): void {
    const refreshToken = this.refreshToken();
    if (refreshToken) {
      // Best effort: the session is cleared locally whether or not the server is reachable.
      this.http.post(`${this.apiBase}/auth/logout`, { refreshToken }).subscribe({
        next: () => undefined,
        error: () => undefined
      });
    }
    this.clear();
  }

  accessToken(): string | null {
    return read(ACCESS_TOKEN_KEY);
  }

  refreshToken(): string | null {
    return read(REFRESH_TOKEN_KEY);
  }

  /** True when the signed-in operator may act on the network, used for showing controls only. */
  canAct(): boolean {
    const role = this.currentUser()?.role;
    return role === 'CONTROLLER' || role === 'ADMIN';
  }

  /**
   * True for an administrator, used for showing the schedule area only.
   *
   * <p>Like {@link canAct}, this decides what is worth putting on screen and nothing else. The API
   * refuses the request either way; hiding a control the server would reject is a courtesy, not a
   * security boundary.
   */
  canAdminister(): boolean {
    return this.currentUser()?.role === 'ADMIN';
  }

  /**
   * True for someone who may upload a feed for review.
   *
   * <p>Planners stage and read; only an administrator puts a feed into service. As with the others,
   * this decides what is worth showing and nothing else.
   */
  canReviewSchedules(): boolean {
    const role = this.currentUser()?.role;
    return role === 'PLANNER' || role === 'ADMIN';
  }

  clear(): void {
    write(ACCESS_TOKEN_KEY, null);
    write(REFRESH_TOKEN_KEY, null);
    write(USER_KEY, null);
    this.currentUser.set(null);
  }

  private store(tokens: AuthTokens): void {
    write(ACCESS_TOKEN_KEY, tokens.accessToken);
    write(REFRESH_TOKEN_KEY, tokens.refreshToken);
    write(USER_KEY, JSON.stringify(tokens.user));
    this.currentUser.set(tokens.user);
  }
}

function read(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) {
      sessionStorage.removeItem(key);
    } else {
      sessionStorage.setItem(key, value);
    }
  } catch {
    // Storage can be unavailable in private windows; the session then lasts until reload.
  }
}

function readUser(): AuthenticatedUser | null {
  const raw = read(USER_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as AuthenticatedUser;
  } catch {
    return null;
  }
}
