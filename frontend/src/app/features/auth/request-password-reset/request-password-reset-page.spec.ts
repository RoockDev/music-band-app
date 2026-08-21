import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RequestPasswordResetPage } from './request-password-reset-page';

describe('RequestPasswordResetPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RequestPasswordResetPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('submits the real CSRF-protected endpoint and renders only the neutral result', () => {
    const fixture = TestBed.createComponent(RequestPasswordResetPage);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const email = element.querySelector<HTMLInputElement>('#reset-request-email')!;
    email.value = 'active@example.com';
    email.dispatchEvent(new Event('input'));
    element.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));

    http.expectOne('/api/auth/csrf').flush('');
    const request = http.expectOne('/api/auth/password-reset/request');
    expect(request.request.body).toEqual({ email: 'active@example.com' });
    request.flush(null, { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();

    expect(element.textContent).toContain('Si existe una cuenta activa asociada a ese correo');
    expect(element.textContent).not.toContain('active@example.com');
  });

  it('does not submit an invalid email and exposes its validation state', () => {
    const fixture = TestBed.createComponent(RequestPasswordResetPage);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const email = element.querySelector<HTMLInputElement>('#reset-request-email')!;
    email.value = 'not-an-email';
    email.dispatchEvent(new Event('input'));
    element.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    expect(email.getAttribute('aria-invalid')).toBe('true');
    expect(element.textContent).toContain('Introduce un correo electrónico válido');
  });
});
