import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Attaches the access token, and refreshes it once when the server says it has expired.
 *
 * <p>Refreshing on a 401 rather than on a timer means the client does not have to agree with the
 * server about what time it is. The retry happens once: if the refreshed token is also rejected, the
 * session is genuinely over and looping would just hammer the API.
 *
 * <p>The auth endpoints themselves are skipped, otherwise a failed refresh would try to refresh.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const isAuthEndpoint = request.url.includes('/auth/');
  const token = auth.accessToken();

  const authorized = token && !isAuthEndpoint
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : request;

  return next(authorized).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status !== 401 || isAuthEndpoint || !auth.refreshToken()) {
        return throwError(() => error);
      }

      return auth.refresh().pipe(
        switchMap((tokens) =>
          next(request.clone({ setHeaders: { Authorization: `Bearer ${tokens.accessToken}` } }))
        ),
        catchError((refreshError) => {
          auth.clear();
          router.navigate(['/login']);
          return throwError(() => refreshError);
        })
      );
    })
  );
};
