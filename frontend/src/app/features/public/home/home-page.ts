import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize, forkJoin } from 'rxjs';
import { BrandService } from '../../../core/config/brand.service';
import { PageState } from '../../../shared/page-state/page-state';
import { CourseAnnouncement, NewsPost, PublicEvent } from '../data/public-content.models';
import { PublicContentService } from '../data/public-content.service';

@Component({
  selector: 'app-home-page',
  imports: [DatePipe, PageState, RouterLink],
  templateUrl: './home-page.html',
  styleUrl: './home-page.scss',
})
export class HomePage implements OnInit {
  protected readonly brand = inject(BrandService).config;
  private readonly content = inject(PublicContentService);
  protected readonly news = signal<NewsPost[]>([]);
  protected readonly events = signal<PublicEvent[]>([]);
  protected readonly courses = signal<CourseAnnouncement[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  ngOnInit(): void {
    this.loadHighlights();
  }

  protected loadHighlights(): void {
    this.loading.set(true);
    this.failed.set(false);
    forkJoin({
      news: this.content.getNews(),
      events: this.content.getEvents(),
      courses: this.content.getCourses(),
    })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: ({ news, events, courses }) => {
          this.news.set(news.slice(0, 2));
          this.events.set(events.slice(0, 2));
          this.courses.set(courses.slice(0, 1));
        },
        error: () => this.failed.set(true),
      });
  }
}
