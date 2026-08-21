import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { landingPath, UserRole } from './auth.models';

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.session()
    ? true
    : router.createUrlTree(['/acceso'], { queryParams: { returnUrl: state.url } });
};

export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const session = auth.session();
  const expectedRole = route.data['role'] as UserRole;

  if (!session) {
    return router.createUrlTree(['/acceso']);
  }
  return session.role === expectedRole || router.createUrlTree([landingPath(session.role)]);
};

export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const session = auth.session();
  return !session || router.createUrlTree([landingPath(session.role)]);
};
