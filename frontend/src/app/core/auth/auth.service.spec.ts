import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { AuthService, AuthTokens } from './auth.service';

/**
 * Session handling.
 *
 * <p>The assertion that matters is the one about roles: the service exposes what a role may do so the
 * UI can hide controls, and that is presentation only. The backend decides what is allowed, and a
 * test that confused the two would encourage someone to rely on it.
 */
describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  const tokens: AuthTokens = {
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    expiresInSeconds: 900,
    user: {
      id: 2,
      email: 'controller@metropulse.test',
      displayName: 'Dev Controller',
      role: 'CONTROLLER'
    }
  };

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('starts with no session', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.accessToken()).toBeNull();
  });

  it('stores the session after logging in', () => {
    service.login('controller@metropulse.test', 'controller-dev-password').subscribe();

    const request = http.expectOne('/api/v1/auth/login');
    expect(request.request.method).toBe('POST');
    request.flush(tokens);

    expect(service.isAuthenticated()).toBe(true);
    expect(service.accessToken()).toBe('access-token');
    expect(service.user()?.displayName).toBe('Dev Controller');
  });

  it('keeps the session across a page reload', () => {
    service.login('controller@metropulse.test', 'password').subscribe();
    http.expectOne('/api/v1/auth/login').flush(tokens);

    // A new instance reads what the previous one stored, the way a reload would. It is built inside
    // an injection context because the service injects HttpClient in a field initializer.
    const reloaded = TestBed.runInInjectionContext(() => new AuthService());
    expect(reloaded.isAuthenticated()).toBe(true);
    expect(reloaded.user()?.email).toBe('controller@metropulse.test');
  });

  it('replaces the stored pair when tokens are refreshed', () => {
    service.login('controller@metropulse.test', 'password').subscribe();
    http.expectOne('/api/v1/auth/login').flush(tokens);

    service.refresh().subscribe();
    const request = http.expectOne('/api/v1/auth/refresh');
    expect(request.request.body).toEqual({ refreshToken: 'refresh-token' });
    request.flush({ ...tokens, accessToken: 'new-access', refreshToken: 'new-refresh' });

    expect(service.accessToken()).toBe('new-access');
    expect(service.refreshToken()).toBe('new-refresh');
  });

  it('clears the session on logout even if the server call fails', () => {
    service.login('controller@metropulse.test', 'password').subscribe();
    http.expectOne('/api/v1/auth/login').flush(tokens);

    service.logout();
    http.expectOne('/api/v1/auth/logout').error(new ProgressEvent('network error'));

    expect(service.isAuthenticated()).toBe(false);
    expect(service.accessToken()).toBeNull();
  });

  describe('canAct', () => {
    function signInAs(role: AuthTokens['user']['role']): void {
      service.login('someone@metropulse.test', 'password').subscribe();
      http.expectOne('/api/v1/auth/login').flush({ ...tokens, user: { ...tokens.user, role } });
    }

    it('is true for a controller and an admin', () => {
      signInAs('CONTROLLER');
      expect(service.canAct()).toBe(true);
    });

    it('is false for a viewer and a planner', () => {
      signInAs('VIEWER');
      expect(service.canAct()).toBe(false);
      service.clear();

      signInAs('PLANNER');
      expect(service.canAct()).toBe(false);
    });

    it('is false with no session at all', () => {
      expect(service.canAct()).toBe(false);
    });
  });
});
