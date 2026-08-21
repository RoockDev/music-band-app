import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AdminUsersPage } from './admin-users-page';

describe('AdminUsersPage permissions', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminUsersPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    http.verify();
  });

  it('offers permission management only for ADMIN accounts and uses readable labels', () => {
    const fixture = TestBed.createComponent(AdminUsersPage);
    fixture.detectChanges();
    http
      .expectOne('/api/users')
      .flush([user(1, 'admin@example.com', 'ADMIN'), user(2, 'musician@example.com', 'MUSICIAN')]);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const permissionButtons = element.querySelectorAll('[data-permissions-user-id]');
    expect(permissionButtons).toHaveLength(1);
    expect(permissionButtons[0].getAttribute('data-permissions-user-id')).toBe('1');

    (permissionButtons[0] as HTMLButtonElement).click();
    http.expectOne('/api/users/1/permissions').flush({ userId: 1, permissions: [] });
    fixture.detectChanges();

    expect(element.textContent).toContain('Gestionar administradores y permisos');
    expect(element.textContent).not.toContain('MANAGE_ADMIN_ROLES');
  });

  it('does not update a toggle before the backend confirms the grant', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fixture = TestBed.createComponent(AdminUsersPage);
    fixture.detectChanges();
    http.expectOne('/api/users').flush([user(3, 'manager@example.com', 'ADMIN')]);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('[data-permissions-user-id="3"]')!.click();
    http.expectOne('/api/users/3/permissions').flush({ userId: 3, permissions: [] });
    fixture.detectChanges();

    const toggle = element.querySelector<HTMLButtonElement>('[data-permission="MANAGE_EVENTS"]')!;
    expect(toggle.textContent).toContain('Activar');
    toggle.click();
    http.expectOne('/api/auth/csrf').flush('');
    const grant = http.expectOne('/api/users/3/permissions/MANAGE_EVENTS');
    fixture.detectChanges();
    expect(toggle.textContent).not.toContain('Desactivar');

    grant.flush({ userId: 3, permissions: ['MANAGE_EVENTS'] });
    fixture.detectChanges();
    expect(toggle.textContent).toContain('Desactivar');
    expect(element.textContent).toContain('Permiso concedido correctamente');
  });

  it('keeps confirmed state and explains a backend conflict', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fixture = TestBed.createComponent(AdminUsersPage);
    fixture.detectChanges();
    http.expectOne('/api/users').flush([user(4, 'conflict@example.com', 'ADMIN')]);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('[data-permissions-user-id="4"]')!.click();
    http.expectOne('/api/users/4/permissions').flush({
      userId: 4,
      permissions: ['MANAGE_ADMIN_ROLES'],
    });
    fixture.detectChanges();

    const toggle = element.querySelector<HTMLButtonElement>(
      '[data-permission="MANAGE_ADMIN_ROLES"]',
    )!;
    toggle.click();
    http.expectOne('/api/auth/csrf').flush('');
    http
      .expectOne('/api/users/4/permissions/MANAGE_ADMIN_ROLES')
      .flush({}, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(toggle.textContent).toContain('Desactivar');
    expect(element.textContent).toContain('entra en conflicto con el estado actual');
  });

  function user(id: number, email: string, role: 'ADMIN' | 'MUSICIAN') {
    return {
      id,
      email,
      role,
      status: 'ACTIVE',
      minor: false,
      guardianContact: null,
      consentOnFile: false,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
  }
});
