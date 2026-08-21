import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';
import { authGuard, guestGuard, roleGuard } from './auth.guards';
import { CurrentUser } from './auth.models';

describe('authentication guards', () => {
  const session = signal<CurrentUser | null>(null);

  beforeEach(() => {
    session.set(null);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { session: session.asReadonly() } },
      ],
    });
  });

  it('sends an anonymous visitor to login with the requested private URL', () => {
    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/musico' } as RouterStateSnapshot),
    ) as UrlTree;

    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/acceso?returnUrl=%2Fmusico');
  });

  it('redirects an authenticated user away from the login route', () => {
    session.set({ id: 1, email: 'music@example.com', role: 'MUSICIAN' });

    const result = TestBed.runInInjectionContext(() =>
      guestGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    ) as UrlTree;

    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/musico');
  });

  it('prevents one role from entering the other role area', () => {
    session.set({ id: 2, email: 'admin@example.com', role: 'ADMIN' });
    const route = { data: { role: 'MUSICIAN' } } as unknown as ActivatedRouteSnapshot;

    const result = TestBed.runInInjectionContext(() =>
      roleGuard(route, {} as RouterStateSnapshot),
    ) as UrlTree;

    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/administracion');
  });
});
