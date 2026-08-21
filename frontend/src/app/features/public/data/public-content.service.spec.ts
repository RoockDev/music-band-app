import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PublicContentService } from './public-content.service';

describe('PublicContentService', () => {
  let service: PublicContentService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PublicContentService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses the real public API endpoints', () => {
    service.getNews().subscribe();
    service.getEvents().subscribe();
    service.getGallery().subscribe();
    service.getVideos().subscribe();
    service.getCourses().subscribe();

    for (const url of [
      '/api/public/news',
      '/api/public/events',
      '/api/public/gallery',
      '/api/public/videos',
      '/api/public/courses',
    ]) {
      http.expectOne(url).flush([]);
    }
  });

  it('bootstraps CSRF before submitting the public contact form', () => {
    const payload = { name: 'Ada', email: 'ada@example.com', message: 'Información, por favor.' };
    service.submitContact(payload).subscribe();

    const csrfRequest = http.expectOne('/api/auth/csrf');
    expect(csrfRequest.request.method).toBe('GET');
    csrfRequest.flush('');

    const contactRequest = http.expectOne('/api/contact');
    expect(contactRequest.request.method).toBe('POST');
    expect(contactRequest.request.body).toEqual(payload);
    contactRequest.flush({ id: 1, ...payload, submittedAt: '2026-08-21T00:00:00Z' });
  });
});
