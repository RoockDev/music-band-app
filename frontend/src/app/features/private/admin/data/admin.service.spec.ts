import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { adminErrorMessage, AdminService } from './admin.service';

describe('AdminService', () => {
  let service: AdminService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses the administrative user read endpoint', () => {
    service.getUsers().subscribe();

    const request = http.expectOne('/api/users');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('bootstraps CSRF before creating a user and preserves the complete payload', () => {
    const payload = {
      email: 'musician@example.com',
      role: 'MUSICIAN' as const,
      minor: false,
      guardianContact: null,
      consentOnFile: false,
    };
    service.createUser(payload).subscribe();

    http.expectOne('/api/auth/csrf').flush('');
    const request = http.expectOne('/api/users');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(payload);
    request.flush({ user: { id: 4 }, activationToken: 'one-time-token' });
  });

  it('uses the group and membership contracts', () => {
    service.getGroups().subscribe();
    service.getGroupMembers(3).subscribe();

    http.expectOne('/api/groups').flush([]);
    http.expectOne('/api/groups/3/musicians').flush([]);
  });

  it('bootstraps CSRF before assigning a musician', () => {
    service.assignMusician(3, 9).subscribe();

    http.expectOne('/api/auth/csrf').flush('');
    const request = http.expectOne('/api/groups/3/musicians/9');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush(null);
  });

  it('uses the remaining user and group mutation endpoints', () => {
    const user = {
      email: 'updated@example.com',
      role: 'MUSICIAN' as const,
      minor: false,
      guardianContact: null,
      consentOnFile: false,
    };
    service.updateUser(7, user).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const updateUser = http.expectOne('/api/users/7');
    expect(updateUser.request.method).toBe('PUT');
    updateUser.flush({ id: 7 });

    service.deactivateUser(7).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const deactivateUser = http.expectOne('/api/users/7/deactivate');
    expect(deactivateUser.request.method).toBe('POST');
    deactivateUser.flush(null);

    service.createGroup({ name: 'Strings', description: null }).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const createGroup = http.expectOne('/api/groups');
    expect(createGroup.request.method).toBe('POST');
    createGroup.flush({ id: 4 });

    service.updateGroup(4, { name: 'Winds', description: 'Section' }).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const updateGroup = http.expectOne('/api/groups/4');
    expect(updateGroup.request.method).toBe('PUT');
    updateGroup.flush({ id: 4 });

    service.deleteGroup(4).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const deleteGroup = http.expectOne('/api/groups/4');
    expect(deleteGroup.request.method).toBe('DELETE');
    deleteGroup.flush(null);

    service.unassignMusician(3, 9).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const unassign = http.expectOne('/api/groups/3/musicians/9');
    expect(unassign.request.method).toBe('DELETE');
    unassign.flush(null);
  });
});

describe('adminErrorMessage', () => {
  it('explains which management permission is missing on a forbidden response', () => {
    const error = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });

    expect(adminErrorMessage(error, 'usuarios')).toBe(
      'Tu cuenta no tiene el permiso necesario para gestionar usuarios.',
    );
  });
});
