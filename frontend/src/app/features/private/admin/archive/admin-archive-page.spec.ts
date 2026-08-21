import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AdminArchivePage } from './admin-archive-page';

describe('AdminArchivePage collection management', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminArchivePage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    http.verify();
  });

  it('rejects metadata longer than the backend column limits', () => {
    const component = TestBed.createComponent(AdminArchivePage).componentInstance as any;
    component.collectionForm.controls.name.setValue('c'.repeat(256));
    component.uploadForm.controls.title.setValue('s'.repeat(256));

    expect(component.collectionForm.controls.name.hasError('maxlength')).toBe(true);
    expect(component.uploadForm.controls.title.hasError('maxlength')).toBe(true);
  });

  it('sends collection version on edit and keeps sheet music untouched', () => {
    const fixture = TestBed.createComponent(AdminArchivePage);
    fixture.detectChanges();
    const collection = {
      id: 2,
      name: 'Marches',
      description: null,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      version: 5,
    };
    http.expectOne('/api/collections').flush([collection]);
    http.expectOne('/api/sheet-music/admin').flush([]);
    fixture.detectChanges();

    const component = fixture.componentInstance as any;
    component.editCollection(collection);
    component.collectionForm.controls.name.setValue('Concerts');
    component.saveCollection();

    http.expectOne('/api/auth/csrf').flush('');
    const update = http.expectOne('/api/collections/2');
    expect(update.request.method).toBe('PUT');
    expect(update.request.body.version).toBe(5);
    update.flush({ ...collection, name: 'Concerts', version: 6 });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Colección actualizada.');
  });

  it('disables deletion when a collection contains sheet music', () => {
    const fixture = TestBed.createComponent(AdminArchivePage);
    fixture.detectChanges();
    http.expectOne('/api/collections').flush([
      {
        id: 2,
        name: 'Marches',
        description: null,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        version: 0,
      },
    ]);
    http.expectOne('/api/sheet-music/admin').flush([
      {
        id: 10,
        title: 'March',
        composer: null,
        collectionId: 2,
        allScope: true,
        groupIds: [],
        musicianIds: [],
        originalFilename: 'march.pdf',
        contentType: 'application/pdf',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        version: 0,
      },
    ]);
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      '[data-delete-collection-id="2"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('1 partituras');
  });

  it('reloads the current collection and score catalog after a stale edit', () => {
    const fixture = TestBed.createComponent(AdminArchivePage);
    fixture.detectChanges();
    const stale = collection({ version: 2 });
    http.expectOne('/api/collections').flush([stale]);
    http.expectOne('/api/sheet-music/admin').flush([]);

    const component = fixture.componentInstance as any;
    component.editCollection(stale);
    component.collectionForm.controls.name.setValue('Attempted name');
    component.saveCollection();
    http.expectOne('/api/auth/csrf').flush('');
    http.expectOne('/api/collections/2').flush(
      { code: 'CONCURRENT_MODIFICATION', error: 'conflict' },
      { status: 409, statusText: 'Conflict' },
    );

    const latest = collection({ name: 'Latest name', version: 3 });
    http.expectOne('/api/collections').flush([latest]);
    http.expectOne('/api/sheet-music/admin').flush([]);
    fixture.detectChanges();

    expect(component.collectionForm.controls.name.value).toBe('Latest name');
    expect(component.editingCollection().version).toBe(3);
    expect(fixture.nativeElement.textContent).toContain('Se han recargado');
  });

  it('updates sheet metadata and scope without replacing the file, then deletes by version', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fixture = TestBed.createComponent(AdminArchivePage);
    fixture.detectChanges();
    const score = sheetMusic({ groupIds: [5], musicianIds: [8], version: 4 });
    http.expectOne('/api/collections').flush([collection()]);
    http.expectOne('/api/sheet-music/admin').flush([score]);
    fixture.detectChanges();

    const component = fixture.componentInstance as any;
    component.editScore(score);
    component.uploadForm.controls.groupIds.setValue('7');
    component.uploadForm.controls.musicianIds.setValue('');
    component.saveScore();
    http.expectOne('/api/auth/csrf').flush('');
    const update = http.expectOne('/api/sheet-music/10');
    expect(update.request.body.groupIds).toEqual([7]);
    expect(update.request.body.musicianIds).toEqual([]);
    expect(update.request.body.version).toBe(4);
    expect(update.request.body.file).toBeUndefined();
    const updated = sheetMusic({ groupIds: [7], musicianIds: [], version: 5 });
    update.flush(updated);
    fixture.detectChanges();

    component.deleteScore(updated);
    http.expectOne('/api/auth/csrf').flush('');
    const remove = http.expectOne('/api/sheet-music/10?version=5');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
    fixture.detectChanges();

    expect(component.scores()).toEqual([]);
    expect(fixture.nativeElement.textContent).toContain('Partitura eliminada.');
  });

  it('reloads the current sheet editor after a stale update', () => {
    const fixture = TestBed.createComponent(AdminArchivePage);
    fixture.detectChanges();
    const stale = sheetMusic({ version: 1 });
    http.expectOne('/api/collections').flush([collection()]);
    http.expectOne('/api/sheet-music/admin').flush([stale]);

    const component = fixture.componentInstance as any;
    component.editScore(stale);
    component.uploadForm.controls.title.setValue('Attempted title');
    component.saveScore();
    http.expectOne('/api/auth/csrf').flush('');
    http.expectOne('/api/sheet-music/10').flush(
      { code: 'CONCURRENT_MODIFICATION', error: 'conflict' },
      { status: 409, statusText: 'Conflict' },
    );

    const latest = sheetMusic({ title: 'Latest title', groupIds: [7], version: 2 });
    http.expectOne('/api/collections').flush([collection()]);
    http.expectOne('/api/sheet-music/admin').flush([latest]);
    fixture.detectChanges();

    expect(component.uploadForm.controls.title.value).toBe('Latest title');
    expect(component.uploadForm.controls.groupIds.value).toBe('7');
    expect(component.editingScore().version).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Se han recargado');
  });
});

function collection(overrides: Record<string, unknown> = {}) {
  return {
    id: 2,
    name: 'Marches',
    description: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: 0,
    ...overrides,
  };
}

function sheetMusic(overrides: Record<string, unknown> = {}) {
  return {
    id: 10,
    title: 'March',
    composer: null,
    collectionId: 2,
    allScope: false,
    groupIds: [],
    musicianIds: [],
    originalFilename: 'march.pdf',
    contentType: 'application/pdf',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: 0,
    ...overrides,
  };
}
