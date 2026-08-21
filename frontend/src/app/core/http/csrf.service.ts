import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { finalize, Observable, shareReplay } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class CsrfService {
  private readonly http = inject(HttpClient);
  private pendingRequest?: Observable<string>;

  ensureToken(): Observable<string> {
    if (!this.pendingRequest) {
      this.pendingRequest = this.http
        .get('/api/auth/csrf', { responseType: 'text' })
        .pipe(
          shareReplay({ bufferSize: 1, refCount: false }),
          finalize(() => {
            this.pendingRequest = undefined;
          }),
        );
    }

    return this.pendingRequest;
  }
}
