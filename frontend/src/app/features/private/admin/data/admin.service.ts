import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import { CsrfService } from '../../../../core/http/csrf.service';
import { InternalEvent, SheetMusic } from '../../data/private-content.models';
import {
  Collection,
  CreateUserResult,
  EventCreateMutation,
  EventUpdateMutation,
  Group,
  GroupMember,
  GroupMutation,
  SheetMusicUpload,
  UserAccount,
  UserMutation,
} from './admin.models';

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);
  private readonly csrf = inject(CsrfService);

  getUsers(): Observable<UserAccount[]> {
    return this.http.get<UserAccount[]>('/api/users');
  }

  createUser(request: UserMutation): Observable<CreateUserResult> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<CreateUserResult>('/api/users', request)));
  }

  updateUser(id: number, request: UserMutation): Observable<UserAccount> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.put<UserAccount>(`/api/users/${id}`, request)));
  }

  deactivateUser(id: number): Observable<void> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<void>(`/api/users/${id}/deactivate`, null)));
  }

  getGroups(): Observable<Group[]> {
    return this.http.get<Group[]>('/api/groups');
  }

  createGroup(request: GroupMutation): Observable<Group> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<Group>('/api/groups', request)));
  }

  updateGroup(id: number, request: GroupMutation): Observable<Group> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.put<Group>(`/api/groups/${id}`, request)));
  }

  deleteGroup(id: number): Observable<void> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.delete<void>(`/api/groups/${id}`)));
  }

  getGroupMembers(id: number): Observable<GroupMember[]> {
    return this.http.get<GroupMember[]>(`/api/groups/${id}/musicians`);
  }

  assignMusician(groupId: number, musicianId: number): Observable<void> {
    return this.csrf
      .ensureToken()
      .pipe(
        switchMap(() =>
          this.http.post<void>(`/api/groups/${groupId}/musicians/${musicianId}`, null),
        ),
      );
  }

  unassignMusician(groupId: number, musicianId: number): Observable<void> {
    return this.csrf
      .ensureToken()
      .pipe(
        switchMap(() => this.http.delete<void>(`/api/groups/${groupId}/musicians/${musicianId}`)),
      );
  }

  getManagedEvents(): Observable<InternalEvent[]> {
    return this.http.get<InternalEvent[]>('/api/events/admin');
  }

  createEvent(request: EventCreateMutation): Observable<InternalEvent> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<InternalEvent>('/api/events', request)));
  }

  updateEvent(id: number, request: EventUpdateMutation): Observable<InternalEvent> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.put<InternalEvent>(`/api/events/${id}`, request)));
  }

  cancelEvent(id: number): Observable<InternalEvent> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<InternalEvent>(`/api/events/${id}/cancel`, null)));
  }

  getCollections(): Observable<Collection[]> {
    return this.http.get<Collection[]>('/api/collections');
  }

  createCollection(request: Pick<Collection, 'name' | 'description'>): Observable<Collection> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<Collection>('/api/collections', request)));
  }

  getManagedSheetMusic(): Observable<SheetMusic[]> {
    return this.http.get<SheetMusic[]>('/api/sheet-music/admin');
  }

  uploadSheetMusic(request: SheetMusicUpload): Observable<SheetMusic> {
    const form = new FormData();
    form.append('title', request.title);
    if (request.composer) {
      form.append('composer', request.composer);
    }
    form.append('collectionId', String(request.collectionId));
    form.append('allScope', String(request.allScope));
    request.groupIds.forEach((id) => form.append('groupIds', String(id)));
    request.musicianIds.forEach((id) => form.append('musicianIds', String(id)));
    form.append('file', request.file);

    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<SheetMusic>('/api/sheet-music', form)));
  }
}

export function adminErrorMessage(error: unknown, area: string): string {
  if (!(error instanceof HttpErrorResponse)) {
    return 'Se ha producido un error inesperado. Inténtalo de nuevo.';
  }

  switch (error.status) {
    case 400:
      return 'Revisa los datos introducidos antes de continuar.';
    case 403:
      return `Tu cuenta no tiene el permiso necesario para gestionar ${area}.`;
    case 404:
      return 'El registro ya no existe o no está disponible.';
    case 409:
      return 'La operación entra en conflicto con el estado actual. Actualiza los datos y vuelve a intentarlo.';
    case 503:
      return 'El servicio no está disponible en este momento. Inténtalo más tarde.';
    default:
      return 'No se ha podido completar la operación. Inténtalo de nuevo.';
  }
}
