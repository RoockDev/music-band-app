import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminContentPage } from './admin-content-page';

describe('AdminContentPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminContentPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends the loaded version when editing a news post', () => {
    const fixture = TestBed.createComponent(AdminContentPage);
    fixture.detectChanges();
    const news = {
      id: 4,
      title: 'Original',
      body: 'Body',
      publishedAt: '2026-01-01T00:00:00Z',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      version: 7,
    };
    http.expectOne('/api/news').flush([news]);
    http.expectOne('/api/videos').flush([]);
    http.expectOne('/api/courses').flush([]);
    http.expectOne('/api/albums').flush([]);
    fixture.detectChanges();

    const component = fixture.componentInstance as any;
    component.editNews(news);
    component.newsForm.controls.title.setValue('Updated');
    component.saveNews();

    http.expectOne('/api/auth/csrf').flush('');
    const update = http.expectOne('/api/news/4');
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({ title: 'Updated', body: 'Body', version: 7 });
    update.flush({ ...news, title: 'Updated', version: 8 });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Noticia actualizada.');
    expect(fixture.nativeElement.textContent).toContain('Updated');
  });

  it('disables album deletion while the album contains photos', () => {
    const fixture = TestBed.createComponent(AdminContentPage);
    fixture.detectChanges();
    http.expectOne('/api/news').flush([]);
    http.expectOne('/api/videos').flush([]);
    http.expectOne('/api/courses').flush([]);
    http.expectOne('/api/albums').flush([
      {
        id: 3,
        name: 'Concert',
        description: null,
        photos: [{ id: 9, caption: null, createdAt: '2026-01-01T00:00:00Z' }],
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        version: 0,
      },
    ]);
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      '[data-delete-album-id="3"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Elimina primero cada fotografía');
  });
});
