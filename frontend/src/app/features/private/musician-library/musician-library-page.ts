import { DOCUMENT } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { PageState } from '../../../shared/page-state/page-state';
import {
  filenameFromContentDisposition,
  MusicianContentService,
} from '../data/musician-content.service';
import { SheetMusic } from '../data/private-content.models';

@Component({
  selector: 'app-musician-library-page',
  imports: [PageState],
  templateUrl: './musician-library-page.html',
  styleUrl: './musician-library-page.scss',
})
export class MusicianLibraryPage implements OnInit {
  private readonly content = inject(MusicianContentService);
  private readonly document = inject(DOCUMENT);
  protected readonly items = signal<SheetMusic[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly downloadingId = signal<number | null>(null);
  protected readonly downloadFailedId = signal<number | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.content
      .getSheetMusic()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (items) => this.items.set([...items].sort((a, b) => a.title.localeCompare(b.title))),
        error: () => this.failed.set(true),
      });
  }

  protected download(item: SheetMusic): void {
    this.downloadingId.set(item.id);
    this.downloadFailedId.set(null);
    this.content
      .downloadSheetMusic(item.id)
      .pipe(finalize(() => this.downloadingId.set(null)))
      .subscribe({
        next: (response) => {
          if (!response.body) {
            this.downloadFailedId.set(item.id);
            return;
          }

          const filename =
            filenameFromContentDisposition(response.headers.get('Content-Disposition')) ??
            item.originalFilename ??
            `partitura-${item.id}`;
          const url = URL.createObjectURL(response.body);
          const anchor = this.document.createElement('a');
          anchor.href = url;
          anchor.download = filename;
          this.document.body.append(anchor);
          anchor.click();
          anchor.remove();
          URL.revokeObjectURL(url);
        },
        error: () => this.downloadFailedId.set(item.id),
      });
  }
}
