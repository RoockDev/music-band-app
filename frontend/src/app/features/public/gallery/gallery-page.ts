import { Component, inject, OnInit, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { PageState } from '../../../shared/page-state/page-state';
import { Album } from '../data/public-content.models';
import { PublicContentService } from '../data/public-content.service';

@Component({
  selector: 'app-gallery-page',
  imports: [PageState],
  templateUrl: './gallery-page.html',
  styleUrl: './gallery-page.scss',
})
export class GalleryPage implements OnInit {
  protected readonly content = inject(PublicContentService);
  protected readonly albums = signal<Album[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.content
      .getGallery()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({ next: (albums) => this.albums.set(albums), error: () => this.failed.set(true) });
  }
}
