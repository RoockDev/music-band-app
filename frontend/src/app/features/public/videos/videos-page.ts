import { Component, inject, OnInit, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { PageState } from '../../../shared/page-state/page-state';
import { VideoLink } from '../data/public-content.models';
import { PublicContentService } from '../data/public-content.service';

@Component({
  selector: 'app-videos-page',
  imports: [PageState],
  templateUrl: './videos-page.html',
  styleUrl: './videos-page.scss',
})
export class VideosPage implements OnInit {
  private readonly content = inject(PublicContentService);
  protected readonly items = signal<VideoLink[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.content
      .getVideos()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({ next: (items) => this.items.set(items), error: () => this.failed.set(true) });
  }
}
