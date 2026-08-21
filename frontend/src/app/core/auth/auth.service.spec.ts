import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('restores the authenticated principal after a reload', () => {
    service.restoreSession().subscribe();

    const request = http.expectOne('/api/auth/me');
    request.flush({ id: 7, email: 'music@example.com', role: 'MUSICIAN' });

    expect(service.session()).toEqual({ id: 7, email: 'music@example.com', role: 'MUSICIAN' });
  });

  it('treats an unauthorized session lookup as anonymous', () => {
    service.restoreSession().subscribe((session) => expect(session).toBeNull());
    http.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(service.session()).toBeNull();
  });

  it('bootstraps CSRF, logs in, and then retrieves the authoritative session', () => {
    service.login({ email: 'admin@example.com', password: 'Secret123!' }).subscribe();

    http.expectOne('/api/auth/csrf').flush('');
    const login = http.expectOne('/api/auth/login');
    expect(login.request.method).toBe('POST');
    login.flush({ email: 'admin@example.com', role: 'ADMIN' });
    http.expectOne('/api/auth/me').flush({ id: 3, email: 'admin@example.com', role: 'ADMIN' });

    expect(service.session()?.role).toBe('ADMIN');
  });

  it('only clears local session after the backend accepts logout', () => {
    service.restoreSession().subscribe();
    http.expectOne('/api/auth/me').flush({ id: 7, email: 'music@example.com', role: 'MUSICIAN' });

    service.logout().subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    expect(service.session()).not.toBeNull();
    http.expectOne('/api/auth/logout').flush(null);

    expect(service.session()).toBeNull();
  });
});
