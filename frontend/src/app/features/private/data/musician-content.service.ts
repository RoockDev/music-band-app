import { HttpClient, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { InternalEvent, SheetMusic } from './private-content.models';

@Injectable({ providedIn: 'root' })
export class MusicianContentService {
  private readonly http = inject(HttpClient);

  getEvents(): Observable<InternalEvent[]> {
    return this.http.get<InternalEvent[]>('/api/events');
  }

  getEvent(id: number): Observable<InternalEvent> {
    return this.http.get<InternalEvent>(`/api/events/${id}`);
  }

  getSheetMusic(): Observable<SheetMusic[]> {
    return this.http.get<SheetMusic[]>('/api/sheet-music');
  }

  downloadSheetMusic(id: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`/api/sheet-music/${id}/file`, {
      observe: 'response',
      responseType: 'blob',
    });
  }
}

export function filenameFromContentDisposition(header: string | null): string | null {
  if (!header) {
    return null;
  }

  const quoted = /filename="((?:\\.|[^"])*)"/i.exec(header);
  if (quoted?.[1]) {
    return quoted[1].replace(/\\([\\"])/g, '$1');
  }

  const plain = /filename=([^;]+)/i.exec(header);
  return plain?.[1]?.trim() || null;
}
