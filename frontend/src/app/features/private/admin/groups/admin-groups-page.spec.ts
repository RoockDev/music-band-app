import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminGroupsPage } from './admin-groups-page';

describe('AdminGroupsPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminGroupsPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('enforces group column limits before submission', () => {
    const component = TestBed.createComponent(AdminGroupsPage).componentInstance as any;
    component.groupForm.controls.name.setValue('g'.repeat(256));
    component.groupForm.controls.description.setValue('d'.repeat(256));

    expect(component.groupForm.controls.name.hasError('maxlength')).toBe(true);
    expect(component.groupForm.controls.description.hasError('maxlength')).toBe(true);
  });

  it('sends the loaded version and reloads the current group after a stale edit', () => {
    const fixture = TestBed.createComponent(AdminGroupsPage);
    fixture.detectChanges();
    const stale = group({ version: 4 });
    http.expectOne('/api/groups').flush([stale]);

    const component = fixture.componentInstance as any;
    component.edit(stale);
    component.groupForm.controls.name.setValue('Attempted name');
    component.submit();
    http.expectOne('/api/auth/csrf').flush('');
    const update = http.expectOne('/api/groups/9');
    expect(update.request.body.version).toBe(4);
    update.flush(
      { code: 'CONCURRENT_MODIFICATION', error: 'conflict' },
      { status: 409, statusText: 'Conflict' },
    );

    const latest = group({ name: 'Latest name', version: 5 });
    http.expectOne('/api/groups').flush([latest]);
    fixture.detectChanges();

    expect(component.groupForm.controls.name.value).toBe('Latest name');
    expect(component.editingGroup().version).toBe(5);
    expect(fixture.nativeElement.textContent).toContain('Se han recargado');
  });
});

function group(overrides: Record<string, unknown> = {}) {
  return {
    id: 9,
    name: 'Original name',
    description: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: 0,
    ...overrides,
  };
}
