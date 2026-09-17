import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

/**
 * Token attachment and the one refresh retry.
 *
 * <p>The behaviour under test is what happens when an access token expires mid-session: the request
 * fails once with a 401, the interceptor refreshes, retries, and the caller never sees the failure.
 * Getting the "once" wrong turns an expired session into a loop that hammers the API.
 */
describe('authInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([])
      ]
    });

    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    controller.verify();
    sessionStorage.clear();
  });

  function signIn(): void {
    auth.login('controller@metropulse.test', 'password').subscribe();
    controller.expectOne('/api/v1/auth/login').flush({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      expiresInSeconds: 900,
      user: { id: 2, email: 'controller@metropulse.test', displayName: 'Dev', role: 'CONTROLLER' }
    });
  }

  it('sends no Authorization header when there is no session', () => {
    http.get('/api/v1/alerts').subscribe();

    const request = controller.expectOne('/api/v1/alerts');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush([]);
  });

  it('attaches the access token', () => {
    signIn();
    http.get('/api/v1/alerts').subscribe();

    const request = controller.expectOne('/api/v1/alerts');
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-1');
    request.flush([]);
  });

  it('does not attach a token to the auth endpoints themselves', () => {
    signIn();
    http.post('/api/v1/auth/refresh', {}).subscribe();

    const request = controller.expectOne('/api/v1/auth/refresh');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('refreshes once on a 401 and retries with the new token', () => {
    signIn();

    let received: unknown = null;
    http.get('/api/v1/alerts').subscribe((response) => (received = response));

    controller.expectOne('/api/v1/alerts').flush(null, { status: 401, statusText: 'Unauthorized' });

    controller.expectOne('/api/v1/auth/refresh').flush({
      accessToken: 'access-2',
      refreshToken: 'refresh-2',
      expiresInSeconds: 900,
      user: { id: 2, email: 'controller@metropulse.test', displayName: 'Dev', role: 'CONTROLLER' }
    });

    const retried = controller.expectOne('/api/v1/alerts');
    expect(retried.request.headers.get('Authorization')).toBe('Bearer access-2');
    retried.flush([{ id: 1 }]);

    expect(received).toEqual([{ id: 1 }] as never);
  });

  it('gives up and clears the session when the refresh itself fails', () => {
    signIn();

    let failed = false;
    http.get('/api/v1/alerts').subscribe({ error: () => (failed = true) });

    controller.expectOne('/api/v1/alerts').flush(null, { status: 401, statusText: 'Unauthorized' });
    controller.expectOne('/api/v1/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });

    // No second retry: looping would hammer the API with a credential that will not work.
    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('passes other failures through untouched', () => {
    signIn();

    let status = 0;
    http.get('/api/v1/alerts').subscribe({ error: (error) => (status = error.status) });

    controller.expectOne('/api/v1/alerts').flush(null, { status: 403, statusText: 'Forbidden' });

    // A 403 means the role is wrong, not the token; refreshing would achieve nothing.
    expect(status).toBe(403);
  });
});
