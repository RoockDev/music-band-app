import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AdminCalendarPage } from './admin-calendar-page';

describe('AdminCalendarPage scope editing', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminCalendarPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    http.verify();
  });

  it('enforces event metadata limits before submission', () => {
    const component = TestBed.createComponent(AdminCalendarPage).componentInstance as any;
    component.eventForm.controls.title.setValue('e'.repeat(256));
    component.eventForm.controls.location.setValue('l'.repeat(256));

    expect(component.eventForm.controls.title.hasError('maxlength')).toBe(true);
    expect(component.eventForm.controls.location.hasError('maxlength')).toBe(true);
  });

  it('preloads the real administrative scope and sends its version and deduplicated targets', () => {
    const fixture = TestBed.createComponent(AdminCalendarPage);
    fixture.detectChanges();
    const event = managedEvent({ groupIds: [3], musicianIds: [8], version: 5 });
    http.expectOne('/api/events/admin').flush([event]);
    http.expectOne('/api/events/admin/targets').flush({
      groups: [{ id: 3, label: 'Brass' }],
      musicians: [
        { id: 8, label: 'first@example.com' },
        { id: 9, label: 'second@example.com' },
      ],
    });
    fixture.detectChanges();

    const component = fixture.componentInstance as any;
    component.edit(event);
    expect(component.eventForm.controls.groupIds.value).toEqual([3]);
    expect(component.eventForm.controls.musicianIds.value).toEqual([8]);
    component.toggleTarget('musicianIds', 9, true);
    component.toggleTarget('musicianIds', 9, true);
    component.submit();

    http.expectOne('/api/auth/csrf').flush('');
    const update = http.expectOne('/api/events/12');
    expect(update.request.method).toBe('PUT');
    expect(update.request.body.version).toBe(5);
    expect(update.request.body.groupIds).toEqual([3]);
    expect(update.request.body.musicianIds).toEqual([8, 9]);
    update.flush({ id: 12 });
    http.expectOne('/api/events/admin').flush([{ ...event, musicianIds: [8, 9], version: 6 }]);
    http.expectOne('/api/events/admin/targets').flush({ groups: [], musicians: [] });
  });

  it('shows impact and requires confirmation before removing recipients', () => {
    const fixture = TestBed.createComponent(AdminCalendarPage);
    fixture.detectChanges();
    const event = managedEvent({ groupIds: [3], musicianIds: [8], version: 2 });
    http.expectOne('/api/events/admin').flush([event]);
    http.expectOne('/api/events/admin/targets').flush({ groups: [], musicians: [] });
    fixture.detectChanges();

    const component = fixture.componentInstance as any;
    component.edit(event);
    component.eventForm.controls.groupIds.setValue([]);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('El alcance cambiará');
    component.submit();
    expect(confirm).toHaveBeenCalledOnce();
    http.expectNone('/api/auth/csrf');
  });

  it('reloads the current version and scope after a conflict', () => {
    const fixture = TestBed.createComponent(AdminCalendarPage);
    fixture.detectChanges();
    const event = managedEvent({ groupIds: [], musicianIds: [], version: 2 });
    http.expectOne('/api/events/admin').flush([event]);
    http.expectOne('/api/events/admin/targets').flush({ groups: [], musicians: [] });
    fixture.detectChanges();

    const component = fixture.componentInstance as any;
    component.edit(event);
    component.eventForm.controls.title.setValue('Stale title');
    component.submit();
    http.expectOne('/api/auth/csrf').flush('');
    http.expectOne('/api/events/12').flush({}, { status: 409, statusText: 'Conflict' });

    const latest = managedEvent({ groupIds: [4], musicianIds: [9], version: 3, title: 'Latest' });
    http.expectOne('/api/events/admin').flush([latest]);
    fixture.detectChanges();

    expect(component.eventForm.controls.title.value).toBe('Latest');
    expect(component.eventForm.controls.groupIds.value).toEqual([4]);
    expect(component.editingEvent().version).toBe(3);
    expect(fixture.nativeElement.textContent).toContain('Se han recargado');
  });

  it('sends the event version when cancelling and reloads after a conflict', () => {
    const fixture = TestBed.createComponent(AdminCalendarPage);
    fixture.detectChanges();
    const event = managedEvent({ version: 4 });
    http.expectOne('/api/events/admin').flush([event]);
    http.expectOne('/api/events/admin/targets').flush({ groups: [], musicians: [] });
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    const component = fixture.componentInstance as any;
    component.cancel(event);
    http.expectOne('/api/auth/csrf').flush('');
    http
      .expectOne('/api/events/12/cancel?version=4')
      .flush({}, { status: 409, statusText: 'Conflict' });

    const latest = managedEvent({ version: 5, title: 'Changed elsewhere' });
    http.expectOne('/api/events/admin').flush([latest]);
    fixture.detectChanges();

    expect(component.events()[0].version).toBe(5);
    expect(component.events()[0].title).toBe('Changed elsewhere');
    expect(fixture.nativeElement.textContent).toContain('Se han recargado');
  });
});

function managedEvent(overrides: Record<string, unknown> = {}) {
  return {
    id: 12,
    title: 'Rehearsal',
    description: null,
    location: 'Hall',
    startsAt: '2026-09-01T18:00:00Z',
    isPublic: false,
    allScope: false,
    status: 'SCHEDULED',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: 0,
    groupIds: [],
    musicianIds: [],
    ...overrides,
  };
}
