import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { PageState } from '../../../../shared/page-state/page-state';
import { InternalEvent } from '../../data/private-content.models';
import { EventCreateMutation, EventUpdateMutation } from '../data/admin.models';
import { parseIdList, toLocalDateTime } from '../data/admin-form.utils';
import { adminErrorMessage, AdminService } from '../data/admin.service';

@Component({
  selector: 'app-admin-calendar-page',
  imports: [DatePipe, PageState, ReactiveFormsModule],
  templateUrl: './admin-calendar-page.html',
  styleUrl: './admin-calendar-page.scss',
})
export class AdminCalendarPage implements OnInit {
  private readonly admin = inject(AdminService);
  private readonly formBuilder = inject(FormBuilder);
  protected readonly events = signal<InternalEvent[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly saving = signal(false);
  protected readonly actionId = signal<number | null>(null);
  protected readonly editingId = signal<number | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly eventForm = this.formBuilder.nonNullable.group({
    title: ['', Validators.required],
    description: '',
    location: '',
    startsAt: ['', Validators.required],
    isPublic: false,
    allScope: false,
    groupIds: '',
    musicianIds: '',
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.admin
      .getManagedEvents()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (events) => this.events.set(this.sort(events)),
        error: () => this.failed.set(true),
      });
  }

  protected edit(event: InternalEvent): void {
    this.editingId.set(event.id);
    this.formError.set(null);
    this.eventForm.setValue({
      title: event.title,
      description: event.description ?? '',
      location: event.location ?? '',
      startsAt: toLocalDateTime(event.startsAt),
      isPublic: event.isPublic,
      allScope: event.allScope,
      groupIds: '',
      musicianIds: '',
    });
  }

  protected resetForm(): void {
    this.editingId.set(null);
    this.formError.set(null);
    this.eventForm.reset({
      title: '',
      description: '',
      location: '',
      startsAt: '',
      isPublic: false,
      allScope: false,
      groupIds: '',
      musicianIds: '',
    });
  }

  protected submit(): void {
    this.formError.set(null);
    if (this.eventForm.invalid) {
      this.eventForm.markAllAsTouched();
      return;
    }

    const value = this.eventForm.getRawValue();
    const startsAt = new Date(value.startsAt);
    if (Number.isNaN(startsAt.getTime())) {
      this.formError.set('Introduce una fecha y una hora válidas.');
      return;
    }

    const common: EventUpdateMutation = {
      title: value.title.trim(),
      description: value.description.trim() || null,
      location: value.location.trim() || null,
      startsAt: startsAt.toISOString(),
      isPublic: value.isPublic,
      allScope: value.allScope,
    };
    const editingId = this.editingId();
    this.saving.set(true);

    if (editingId !== null) {
      this.admin
        .updateEvent(editingId, common)
        .pipe(finalize(() => this.saving.set(false)))
        .subscribe({
          next: (updated) => {
            this.events.update((events) =>
              this.sort(events.map((event) => (event.id === updated.id ? updated : event))),
            );
            this.resetForm();
          },
          error: (error: unknown) => this.formError.set(adminErrorMessage(error, 'eventos')),
        });
      return;
    }

    const groupIds = parseIdList(value.groupIds);
    const musicianIds = parseIdList(value.musicianIds);
    if (groupIds === null || musicianIds === null) {
      this.saving.set(false);
      this.formError.set(
        'Los alcances deben contener identificadores positivos separados por comas.',
      );
      return;
    }
    const request: EventCreateMutation = { ...common, groupIds, musicianIds };
    this.admin
      .createEvent(request)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (created) => {
          this.events.update((events) => this.sort([...events, created]));
          this.resetForm();
        },
        error: (error: unknown) => this.formError.set(adminErrorMessage(error, 'eventos')),
      });
  }

  protected cancel(event: InternalEvent): void {
    if (!window.confirm(`¿Cancelar el evento ${event.title}? Se conservará en el historial.`)) {
      return;
    }

    this.actionId.set(event.id);
    this.actionError.set(null);
    this.admin
      .cancelEvent(event.id)
      .pipe(finalize(() => this.actionId.set(null)))
      .subscribe({
        next: (cancelled) =>
          this.events.update((events) =>
            events.map((item) => (item.id === cancelled.id ? cancelled : item)),
          ),
        error: (error: unknown) => this.actionError.set(adminErrorMessage(error, 'eventos')),
      });
  }

  private sort(events: InternalEvent[]): InternalEvent[] {
    return [...events].sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  }
}
