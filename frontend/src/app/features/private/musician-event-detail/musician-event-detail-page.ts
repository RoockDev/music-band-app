import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { PageState } from '../../../shared/page-state/page-state';
import { InternalEvent } from '../data/private-content.models';
import { MusicianContentService } from '../data/musician-content.service';

@Component({
  selector: 'app-musician-event-detail-page',
  imports: [DatePipe, PageState, RouterLink],
  templateUrl: './musician-event-detail-page.html',
  styleUrl: './musician-event-detail-page.scss',
})
export class MusicianEventDetailPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly content = inject(MusicianContentService);
  private readonly eventId = Number(this.route.snapshot.paramMap.get('id'));
  protected readonly item = signal<InternalEvent | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    if (!Number.isInteger(this.eventId) || this.eventId <= 0) {
      this.loading.set(false);
      this.failed.set(true);
      return;
    }

    this.loading.set(true);
    this.failed.set(false);
    this.content
      .getEvent(this.eventId)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({ next: (item) => this.item.set(item), error: () => this.failed.set(true) });
  }
}
