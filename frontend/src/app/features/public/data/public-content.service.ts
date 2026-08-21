import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import { CsrfService } from '../../../core/http/csrf.service';
import {
  Album,
  ContactRequest,
  ContactSubmission,
  CourseAnnouncement,
  NewsPost,
  PublicEvent,
  VideoLink,
} from './public-content.models';

@Injectable({ providedIn: 'root' })
export class PublicContentService {
  private readonly http = inject(HttpClient);
  private readonly csrf = inject(CsrfService);

  getNews(): Observable<NewsPost[]> {
    return this.http.get<NewsPost[]>('/api/public/news');
  }

  getEvents(): Observable<PublicEvent[]> {
    return this.http.get<PublicEvent[]>('/api/public/events');
  }

  getGallery(): Observable<Album[]> {
    return this.http.get<Album[]>('/api/public/gallery');
  }

  getVideos(): Observable<VideoLink[]> {
    return this.http.get<VideoLink[]>('/api/public/videos');
  }

  getCourses(): Observable<CourseAnnouncement[]> {
    return this.http.get<CourseAnnouncement[]>('/api/public/courses');
  }

  submitContact(request: ContactRequest): Observable<ContactSubmission> {
    return this.csrf.ensureToken().pipe(
      switchMap(() => this.http.post<ContactSubmission>('/api/contact', request)),
    );
  }

  photoUrl(photoId: number): string {
    return `/api/public/gallery/photos/${photoId}/file`;
  }
}
