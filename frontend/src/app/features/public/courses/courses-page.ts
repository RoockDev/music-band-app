import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { PageState } from '../../../shared/page-state/page-state';
import { CourseAnnouncement } from '../data/public-content.models';
import { PublicContentService } from '../data/public-content.service';

@Component({
  selector: 'app-courses-page',
  imports: [DatePipe, DecimalPipe, PageState],
  templateUrl: './courses-page.html',
  styleUrl: './courses-page.scss',
})
export class CoursesPage implements OnInit {
  private readonly content = inject(PublicContentService);
  protected readonly items = signal<CourseAnnouncement[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.content
      .getCourses()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({ next: (items) => this.items.set(items), error: () => this.failed.set(true) });
  }
}
