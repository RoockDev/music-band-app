import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, switchMap } from 'rxjs';
import { CsrfService } from '../../../../core/http/csrf.service';
import {
  Album,
  CourseAnnouncement,
  NewsPost,
  Photo,
  VideoLink,
} from '../../../public/data/public-content.models';
import { InternalEvent, SheetMusic } from '../../data/private-content.models';
import {
  AdminPermission,
  AdminPermissions,
  AuditLog,
  Collection,
  CollectionMutation,
  CourseMutation,
  CreateUserResult,
  EventCreateMutation,
  EventCancellation,
  EventTargetCatalog,
  EventUpdateMutation,
  Group,
  GroupMember,
  GroupMutation,
  GroupUpdateMutation,
  ManagedEvent,
  SheetMusicUpload,
  UserAccount,
  UserMutation,
  UserUpdateMutation,
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

  updateUser(id: number, request: UserUpdateMutation): Observable<UserAccount> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.put<UserAccount>(`/api/users/${id}`, request)));
  }

  deactivateUser(id: number): Observable<void> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<void>(`/api/users/${id}/deactivate`, null)));
  }

  getAdminPermissions(id: number): Observable<AdminPermissions> {
    return this.http.get<AdminPermissions>(`/api/users/${id}/permissions`);
  }

  grantAdminPermission(id: number, permission: AdminPermission): Observable<AdminPermissions> {
    return this.csrf
      .ensureToken()
      .pipe(
        switchMap(() =>
          this.http.put<AdminPermissions>(`/api/users/${id}/permissions/${permission}`, null),
        ),
      );
  }

  revokeAdminPermission(id: number, permission: AdminPermission): Observable<AdminPermissions> {
    return this.csrf
      .ensureToken()
      .pipe(
        switchMap(() =>
          this.http.delete<AdminPermissions>(`/api/users/${id}/permissions/${permission}`),
        ),
      );
  }

  getGroups(): Observable<Group[]> {
    return this.http.get<Group[]>('/api/groups');
  }

  createGroup(request: GroupMutation): Observable<Group> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<Group>('/api/groups', request)));
  }

  updateGroup(id: number, request: GroupUpdateMutation): Observable<Group> {
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

  getManagedEvents(): Observable<ManagedEvent[]> {
    return this.http.get<ManagedEvent[]>('/api/events/admin');
  }

  getEventTargets(): Observable<EventTargetCatalog> {
    return this.http.get<EventTargetCatalog>('/api/events/admin/targets');
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

  cancelEvent(id: number, version: number): Observable<EventCancellation> {
    return this.csrf
      .ensureToken()
      .pipe(
        switchMap(() =>
          this.http.post<EventCancellation>(`/api/events/${id}/cancel`, null, {
            params: { version },
          }),
        ),
      );
  }

  getCollections(): Observable<Collection[]> {
    return this.http.get<Collection[]>('/api/collections');
  }

  createCollection(request: Pick<Collection, 'name' | 'description'>): Observable<Collection> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<Collection>('/api/collections', request)));
  }

  updateCollection(id: number, request: Required<CollectionMutation>): Observable<Collection> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.put<Collection>(`/api/collections/${id}`, request)));
  }

  deleteCollection(id: number, version: number): Observable<void> {
    return this.csrf
      .ensureToken()
      .pipe(
        switchMap(() => this.http.delete<void>(`/api/collections/${id}`, { params: { version } })),
      );
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

  createNews(request: { title: string; body: string }): Observable<NewsPost> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<NewsPost>('/api/news', request)));
  }

  getManagedNews(): Observable<NewsPost[]> {
    return this.http.get<NewsPost[]>('/api/news');
  }

  updateNews(
    id: number,
    request: { title: string; body: string; version: number },
  ): Observable<NewsPost> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.put<NewsPost>(`/api/news/${id}`, request)));
  }

  deleteNews(id: number, version: number): Observable<void> {
    return this.deleteVersioned(`/api/news/${id}`, version);
  }

  createVideo(request: { title: string; url: string }): Observable<VideoLink> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<VideoLink>('/api/videos', request)));
  }

  getManagedVideos(): Observable<VideoLink[]> {
    return this.http.get<VideoLink[]>('/api/videos');
  }

  updateVideo(
    id: number,
    request: { title: string; url: string; version: number },
  ): Observable<VideoLink> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.put<VideoLink>(`/api/videos/${id}`, request)));
  }

  deleteVideo(id: number, version: number): Observable<void> {
    return this.deleteVersioned(`/api/videos/${id}`, version);
  }

  createCourse(request: CourseMutation): Observable<CourseAnnouncement> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<CourseAnnouncement>('/api/courses', request)));
  }

  getManagedCourses(): Observable<CourseAnnouncement[]> {
    return this.http.get<CourseAnnouncement[]>('/api/courses');
  }

  updateCourse(id: number, request: Required<CourseMutation>): Observable<CourseAnnouncement> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.put<CourseAnnouncement>(`/api/courses/${id}`, request)));
  }

  deleteCourse(id: number, version: number): Observable<void> {
    return this.deleteVersioned(`/api/courses/${id}`, version);
  }

  createAlbum(request: { name: string; description: string | null }): Observable<Album> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<Album>('/api/albums', request)));
  }

  getManagedAlbums(): Observable<Album[]> {
    return this.http.get<Album[]>('/api/albums');
  }

  updateAlbum(
    id: number,
    request: { name: string; description: string | null; version: number },
  ): Observable<Album> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.put<Album>(`/api/albums/${id}`, request)));
  }

  deleteAlbum(id: number, version: number): Observable<void> {
    return this.deleteVersioned(`/api/albums/${id}`, version);
  }

  uploadPhoto(albumId: number, file: File, caption: string | null): Observable<Photo> {
    const form = new FormData();
    if (caption) {
      form.append('caption', caption);
    }
    form.append('file', file);
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.post<Photo>(`/api/albums/${albumId}/photos`, form)));
  }

  deletePhoto(photoId: number): Observable<void> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.delete<void>(`/api/albums/photos/${photoId}`)));
  }

  getAuditHistory(entityType: string, entityId: number): Observable<AuditLog[]> {
    return this.http.get<AuditLog[]>(`/api/audit/${encodeURIComponent(entityType)}/${entityId}`);
  }

  private deleteVersioned(url: string, version: number): Observable<void> {
    return this.csrf
      .ensureToken()
      .pipe(switchMap(() => this.http.delete<void>(url, { params: { version } })));
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

export function isConcurrentModification(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse) || error.status !== 409) {
    return false;
  }
  const body: unknown = error.error;
  return (
    typeof body === 'object' &&
    body !== null &&
    'code' in body &&
    body.code === 'CONCURRENT_MODIFICATION'
  );
}
