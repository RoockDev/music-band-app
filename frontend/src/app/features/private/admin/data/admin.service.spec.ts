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

  it('reads and mutates typed admin permissions with CSRF on writes', () => {
    service.getAdminPermissions(7).subscribe();
    const getPermissions = http.expectOne('/api/users/7/permissions');
    expect(getPermissions.request.method).toBe('GET');
    getPermissions.flush({ userId: 7, permissions: ['MANAGE_USERS'] });

    service.grantAdminPermission(7, 'MANAGE_EVENTS').subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const grant = http.expectOne('/api/users/7/permissions/MANAGE_EVENTS');
    expect(grant.request.method).toBe('PUT');
    expect(grant.request.body).toBeNull();
    grant.flush({ userId: 7, permissions: ['MANAGE_EVENTS', 'MANAGE_USERS'] });

    service.revokeAdminPermission(7, 'MANAGE_EVENTS').subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const revoke = http.expectOne('/api/users/7/permissions/MANAGE_EVENTS');
    expect(revoke.request.method).toBe('DELETE');
    revoke.flush({ userId: 7, permissions: ['MANAGE_USERS'] });
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

  it('uses separate permission-gated management catalogs for events and sheet music', () => {
    service.getManagedEvents().subscribe();
    service.getCollections().subscribe();
    service.getManagedSheetMusic().subscribe();

    http.expectOne('/api/events/admin').flush([]);
    http.expectOne('/api/collections').flush([]);
    http.expectOne('/api/sheet-music/admin').flush([]);
  });

  it('serializes a sheet-music upload as multipart form data after CSRF bootstrap', () => {
    const file = new File(['score'], 'suite.pdf', { type: 'application/pdf' });
    service
      .uploadSheetMusic({
        title: 'Suite',
        composer: 'Composer',
        collectionId: 2,
        allScope: false,
        groupIds: [3, 4],
        musicianIds: [8],
        file,
      })
      .subscribe();

    http.expectOne('/api/auth/csrf').flush('');
    const request = http.expectOne('/api/sheet-music');
    expect(request.request.method).toBe('POST');
    expect(request.request.body.get('title')).toBe('Suite');
    expect(request.request.body.getAll('groupIds')).toEqual(['3', '4']);
    expect(request.request.body.get('file')).toBe(file);
    request.flush({ id: 10 });
  });

  it('uses the event lifecycle and collection creation contracts', () => {
    const event = {
      title: 'Rehearsal',
      description: null,
      location: 'Hall',
      startsAt: '2026-09-01T18:00:00Z',
      isPublic: false,
      allScope: true,
      groupIds: [],
      musicianIds: [],
    };
    service.createEvent(event).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const createEvent = http.expectOne('/api/events');
    expect(createEvent.request.method).toBe('POST');
    expect(createEvent.request.body).toEqual(event);
    createEvent.flush({ id: 2 });

    service.cancelEvent(2).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const cancelEvent = http.expectOne('/api/events/2/cancel');
    expect(cancelEvent.request.method).toBe('POST');
    cancelEvent.flush({ id: 2, status: 'CANCELLED' });

    service.createCollection({ name: 'Concerts', description: null }).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const createCollection = http.expectOne('/api/collections');
    expect(createCollection.request.method).toBe('POST');
    createCollection.flush({ id: 3 });
  });

  it('uses create-only content endpoints and the entity audit history', () => {
    service.createNews({ title: 'News', body: 'Body' }).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const news = http.expectOne('/api/news');
    expect(news.request.method).toBe('POST');
    news.flush({ id: 1 });

    service.createVideo({ title: 'Concert', url: 'https://example.com/video' }).subscribe();
    http.expectOne('/api/auth/csrf').flush('');
    const video = http.expectOne('/api/videos');
    expect(video.request.method).toBe('POST');
    video.flush({ id: 2 });

    service.getAuditHistory('UserAccount', 7).subscribe();
    const audit = http.expectOne('/api/audit/UserAccount/7');
    expect(audit.request.method).toBe('GET');
    audit.flush([]);
  });
});

describe('adminErrorMessage', () => {
  it('explains which management permission is missing on a forbidden response', () => {
    const error = new HttpErrorResponse({ status: 403, statusText: 'Forbidden' });

    expect(adminErrorMessage(error, 'usuarios')).toBe(
      'Tu cuenta no tiene el permiso necesario para gestionar usuarios.',
    );
  });

  it('distinguishes missing targets from state conflicts', () => {
    const missing = new HttpErrorResponse({ status: 404, statusText: 'Not Found' });
    const conflict = new HttpErrorResponse({ status: 409, statusText: 'Conflict' });

    expect(adminErrorMessage(missing, 'permisos administrativos')).toContain('ya no existe');
    expect(adminErrorMessage(conflict, 'permisos administrativos')).toContain(
      'entra en conflicto con el estado actual',
    );
  });
});
