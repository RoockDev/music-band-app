import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { PageState } from '../../../shared/page-state/page-state';
import { InternalEvent } from '../data/private-content.models';
import { MusicianContentService } from '../data/musician-content.service';

@Component({
  selector: 'app-musician-events-page',
  imports: [DatePipe, PageState, RouterLink],
  templateUrl: './musician-events-page.html',
  styleUrl: './musician-events-page.scss',
})
export class MusicianEventsPage implements OnInit {
  private readonly content = inject(MusicianContentService);
  protected readonly items = signal<InternalEvent[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.content
      .getEvents()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (items) =>
          this.items.set(
            [...items].sort((left, right) => left.startsAt.localeCompare(right.startsAt)),
          ),
        error: () => this.failed.set(true),
      });
  }
}
