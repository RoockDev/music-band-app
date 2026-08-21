import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { PageState } from '../../../shared/page-state/page-state';
import { NewsPost } from '../data/public-content.models';
import { PublicContentService } from '../data/public-content.service';

@Component({
  selector: 'app-news-page',
  imports: [DatePipe, PageState],
  templateUrl: './news-page.html',
})
export class NewsPage implements OnInit {
  private readonly content = inject(PublicContentService);
  protected readonly items = signal<NewsPost[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.content
      .getNews()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({ next: (items) => this.items.set(items), error: () => this.failed.set(true) });
  }
}
