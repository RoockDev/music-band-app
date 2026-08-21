import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { filenameFromContentDisposition, MusicianContentService } from './musician-content.service';

describe('MusicianContentService', () => {
  let service: MusicianContentService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MusicianContentService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses the scoped internal event endpoints', () => {
    service.getEvents().subscribe();
    service.getEvent(12).subscribe();

    const list = http.expectOne('/api/events');
    expect(list.request.method).toBe('GET');
    list.flush([]);

    const detail = http.expectOne('/api/events/12');
    expect(detail.request.method).toBe('GET');
    detail.flush({ id: 12 });
  });

  it('retrieves accessible sheet music and downloads the protected file as a blob', () => {
    service.getSheetMusic().subscribe();
    service.downloadSheetMusic(8).subscribe();

    const catalog = http.expectOne('/api/sheet-music');
    expect(catalog.request.method).toBe('GET');
    catalog.flush([]);

    const download = http.expectOne('/api/sheet-music/8/file');
    expect(download.request.method).toBe('GET');
    expect(download.request.responseType).toBe('blob');
    download.flush(new Blob(['score']), {
      headers: { 'Content-Disposition': 'attachment; filename="score.pdf"' },
    });
  });
});

describe('filenameFromContentDisposition', () => {
  it('reads quoted filenames and unescapes protected quotes', () => {
    expect(filenameFromContentDisposition('attachment; filename="Suite \\"No. 1\\".pdf"')).toBe(
      'Suite "No. 1".pdf',
    );
  });

  it('returns null when the response has no filename', () => {
    expect(filenameFromContentDisposition(null)).toBeNull();
    expect(filenameFromContentDisposition('attachment')).toBeNull();
  });
});
