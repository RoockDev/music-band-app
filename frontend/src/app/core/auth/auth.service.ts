import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { catchError, finalize, Observable, of, shareReplay, switchMap, tap } from 'rxjs';
import { CsrfService } from '../http/csrf.service';
import {
  CurrentUser,
  LoginCredentials,
  PasswordCompletion,
  PasswordResetRequest,
} from './auth.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly csrf = inject(CsrfService);
  private readonly currentSession = signal<CurrentUser | null>(null);
  private pendingRestoration?: Observable<CurrentUser | null>;

  readonly session = this.currentSession.asReadonly();

  restoreSession(force = false): Observable<CurrentUser | null> {
    if (!force && this.pendingRestoration) {
      return this.pendingRestoration;
    }

    this.pendingRestoration = this.http.get<CurrentUser>('/api/auth/me').pipe(
      tap((user) => this.currentSession.set(user)),
      catchError(() => {
        this.currentSession.set(null);
        return of(null);
      }),
      finalize(() => {
        this.pendingRestoration = undefined;
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.pendingRestoration;
  }

  login(credentials: LoginCredentials): Observable<CurrentUser | null> {
    return this.csrf.ensureToken().pipe(
      switchMap(() => this.http.post('/api/auth/login', credentials)),
      switchMap(() => this.restoreSession(true)),
    );
  }

  logout(): Observable<void> {
    return this.csrf.ensureToken().pipe(
      switchMap(() => this.http.post<void>('/api/auth/logout', {})),
      tap(() => this.currentSession.set(null)),
    );
  }

  activateAccount(request: PasswordCompletion): Observable<void> {
    return this.csrf.ensureToken().pipe(
      switchMap(() => this.http.post<void>('/api/auth/activate', request)),
    );
  }

  completePasswordReset(request: PasswordCompletion): Observable<void> {
    return this.csrf.ensureToken().pipe(
      switchMap(() => this.http.post<void>('/api/auth/password-reset/complete', request)),
    );
  }

  requestPasswordReset(request: PasswordResetRequest): Observable<void> {
    return this.csrf.ensureToken().pipe(
      switchMap(() => this.http.post<void>('/api/auth/password-reset/request', request)),
    );
  }
}
