import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, forkJoin } from 'rxjs';
import { PageState } from '../../../../shared/page-state/page-state';
import {
  EventCreateMutation,
  EventTargetCatalog,
  EventUpdateMutation,
  ManagedEvent,
} from '../data/admin.models';
import { toLocalDateTime } from '../data/admin-form.utils';
import { adminErrorMessage, AdminService } from '../data/admin.service';
import {
  deduplicateIds,
  describeScopeImpact,
  EventScopeSelection,
  eventScopeValidator,
  scopeRemovesRecipients,
} from './event-scope.utils';

@Component({
  selector: 'app-admin-calendar-page',
  imports: [DatePipe, PageState, ReactiveFormsModule],
  templateUrl: './admin-calendar-page.html',
  styleUrl: './admin-calendar-page.scss',
})
export class AdminCalendarPage implements OnInit {
  private readonly admin = inject(AdminService);
  private readonly formBuilder = inject(FormBuilder);
  protected readonly events = signal<ManagedEvent[]>([]);
  protected readonly targets = signal<EventTargetCatalog>({ groups: [], musicians: [] });
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly saving = signal(false);
  protected readonly actionId = signal<number | null>(null);
  protected readonly editingId = signal<number | null>(null);
  protected readonly editingEvent = signal<ManagedEvent | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly eventForm = this.formBuilder.nonNullable.group(
    {
      title: ['', Validators.required],
      description: '',
      location: '',
      startsAt: ['', Validators.required],
      isPublic: false,
      allScope: false,
      groupIds: this.formBuilder.nonNullable.control<number[]>([]),
      musicianIds: this.formBuilder.nonNullable.control<number[]>([]),
    },
    { validators: eventScopeValidator },
  );

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    forkJoin({ events: this.admin.getManagedEvents(), targets: this.admin.getEventTargets() })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: ({ events, targets }) => {
          this.events.set(this.sort(events));
          this.targets.set(targets);
        },
        error: () => this.failed.set(true),
      });
  }

  protected edit(event: ManagedEvent): void {
    this.editingId.set(event.id);
    this.editingEvent.set(event);
    this.formError.set(null);
    this.eventForm.setValue({
      title: event.title,
      description: event.description ?? '',
      location: event.location ?? '',
      startsAt: toLocalDateTime(event.startsAt),
      isPublic: event.isPublic,
      allScope: event.allScope,
      groupIds: [...event.groupIds],
      musicianIds: [...event.musicianIds],
    });
  }

  protected resetForm(): void {
    this.editingId.set(null);
    this.editingEvent.set(null);
    this.formError.set(null);
    this.eventForm.reset({
      title: '',
      description: '',
      location: '',
      startsAt: '',
      isPublic: false,
      allScope: false,
      groupIds: [],
      musicianIds: [],
    });
  }

  protected globalScopeChanged(): void {
    if (this.eventForm.controls.allScope.value) {
      this.eventForm.patchValue({ groupIds: [], musicianIds: [] });
    }
  }

  protected targetSelected(kind: 'groupIds' | 'musicianIds', id: number): boolean {
    return this.eventForm.controls[kind].value.includes(id);
  }

  protected toggleTarget(kind: 'groupIds' | 'musicianIds', id: number, checked: boolean): void {
    if (this.eventForm.controls.allScope.value) {
      return;
    }
    const current = this.eventForm.controls[kind].value;
    this.eventForm.controls[kind].setValue(
      checked ? deduplicateIds([...current, id]) : current.filter((value) => value !== id),
    );
  }

  protected scopeImpact(): string {
    return describeScopeImpact(this.originalScope(), this.currentScope());
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

    const scope = this.currentScope();
    const common: EventCreateMutation = {
      title: value.title.trim(),
      description: value.description.trim() || null,
      location: value.location.trim() || null,
      startsAt: startsAt.toISOString(),
      isPublic: value.isPublic,
      allScope: value.allScope,
      groupIds: scope.groupIds,
      musicianIds: scope.musicianIds,
    };
    const editingId = this.editingId();

    if (editingId !== null) {
      const editingEvent = this.editingEvent();
      if (editingEvent === null) {
        this.formError.set('Vuelve a seleccionar el evento antes de guardar.');
        return;
      }
      const original = this.originalScope();
      if (
        original !== null &&
        scopeRemovesRecipients(original, scope) &&
        !window.confirm(
          'Este cambio puede quitar acceso al evento a grupos o músicos. ¿Quieres continuar?',
        )
      ) {
        return;
      }

      const request: EventUpdateMutation = { ...common, version: editingEvent.version };
      this.saving.set(true);
      this.admin
        .updateEvent(editingId, request)
        .pipe(finalize(() => this.saving.set(false)))
        .subscribe({
          next: () => {
            this.resetForm();
            this.load();
          },
          error: (error: unknown) => this.handleUpdateError(error, editingId),
        });
      return;
    }

    this.saving.set(true);
    this.admin
      .createEvent(common)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.resetForm();
          this.load();
        },
        error: (error: unknown) => this.formError.set(adminErrorMessage(error, 'eventos')),
      });
  }

  protected cancel(event: ManagedEvent): void {
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
            events.map((item) => (item.id === cancelled.id ? { ...item, ...cancelled } : item)),
          ),
        error: (error: unknown) => this.actionError.set(adminErrorMessage(error, 'eventos')),
      });
  }

  private handleUpdateError(error: unknown, eventId: number): void {
    if (!(error instanceof HttpErrorResponse) || error.status !== 409) {
      this.formError.set(adminErrorMessage(error, 'eventos'));
      return;
    }

    this.admin.getManagedEvents().subscribe({
      next: (events) => {
        const sorted = this.sort(events);
        this.events.set(sorted);
        const latest = sorted.find((event) => event.id === eventId);
        if (latest) {
          this.edit(latest);
          this.formError.set(
            'Otra persona modificó el evento. Se han recargado su versión y alcance actuales.',
          );
        } else {
          this.resetForm();
          this.formError.set('El evento ya no existe o no está disponible.');
        }
      },
      error: () =>
        this.formError.set(
          'Hay un conflicto de edición y no se han podido recargar los datos actuales.',
        ),
    });
  }

  private currentScope(): EventScopeSelection {
    return {
      allScope: this.eventForm.controls.allScope.value,
      groupIds: deduplicateIds(this.eventForm.controls.groupIds.value),
      musicianIds: deduplicateIds(this.eventForm.controls.musicianIds.value),
    };
  }

  private originalScope(): EventScopeSelection | null {
    const event = this.editingEvent();
    return event
      ? { allScope: event.allScope, groupIds: event.groupIds, musicianIds: event.musicianIds }
      : null;
  }

  private sort(events: ManagedEvent[]): ManagedEvent[] {
    return [...events].sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  }
}
