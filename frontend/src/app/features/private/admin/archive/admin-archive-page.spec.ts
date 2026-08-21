import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
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

  afterEach(() => http.verify());

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
        originalFilename: 'march.pdf',
        contentType: 'application/pdf',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    ]);
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      '[data-delete-collection-id="2"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('1 partituras');
  });
});
