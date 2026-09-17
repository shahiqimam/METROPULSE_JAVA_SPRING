import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Keeps unauthenticated visitors off the control screens.
 *
 * <p>This is for the user's benefit, not for security: it stops someone landing on an empty
 * dashboard that fails every request. The backend rejects unauthenticated calls regardless, which is
 * where authorisation actually happens.
 */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }

  router.navigate(['/login']);
  return false;
};
