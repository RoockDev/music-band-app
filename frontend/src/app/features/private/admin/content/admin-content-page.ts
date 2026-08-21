import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, forkJoin, Observable } from 'rxjs';
import { PageState } from '../../../../shared/page-state/page-state';
import {
  Album,
  CourseAnnouncement,
  NewsPost,
  Photo,
  VideoLink,
} from '../../../public/data/public-content.models';
import { CourseMutation } from '../data/admin.models';
import {
  adminErrorMessage,
  AdminService,
  isConcurrentModification,
} from '../data/admin.service';

type ContentKind = 'news' | 'video' | 'course' | 'album' | 'photo';
const MAX_FILE_SIZE = 20 * 1024 * 1024;

@Component({
  selector: 'app-admin-content-page',
  imports: [PageState, ReactiveFormsModule],
  templateUrl: './admin-content-page.html',
  styleUrl: './admin-content-page.scss',
})
export class AdminContentPage implements OnInit {
  private readonly admin = inject(AdminService);
  private readonly formBuilder = inject(FormBuilder);
  private photoFileInput: HTMLInputElement | null = null;
  protected readonly news = signal<NewsPost[]>([]);
  protected readonly videos = signal<VideoLink[]>([]);
  protected readonly courses = signal<CourseAnnouncement[]>([]);
  protected readonly albums = signal<Album[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly saving = signal<ContentKind | null>(null);
  protected readonly errors = signal<Partial<Record<ContentKind, string>>>({});
  protected readonly notice = signal<string | null>(null);
  protected readonly editingNews = signal<NewsPost | null>(null);
  protected readonly editingVideo = signal<VideoLink | null>(null);
  protected readonly editingCourse = signal<CourseAnnouncement | null>(null);
  protected readonly editingAlbum = signal<Album | null>(null);
  protected readonly photoFile = signal<File | null>(null);
  protected readonly newsForm = this.formBuilder.nonNullable.group({
    title: ['', Validators.required],
    body: ['', Validators.required],
  });
  protected readonly videoForm = this.formBuilder.nonNullable.group({
    title: ['', Validators.required],
    url: ['', Validators.required],
  });
  protected readonly courseForm = this.formBuilder.nonNullable.group({
    title: ['', Validators.required],
    description: '',
    startDate: ['', Validators.required],
    endDate: '',
    price: [0, Validators.min(0)],
    instrument: ['', Validators.required],
    minimumAge: [0, Validators.min(0)],
  });
  protected readonly albumForm = this.formBuilder.nonNullable.group({
    name: ['', Validators.required],
    description: '',
  });
  protected readonly photoForm = this.formBuilder.nonNullable.group({
    albumId: [0, Validators.min(1)],
    caption: '',
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    forkJoin({
      news: this.admin.getManagedNews(),
      videos: this.admin.getManagedVideos(),
      courses: this.admin.getManagedCourses(),
      albums: this.admin.getManagedAlbums(),
    })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (content) => {
          this.news.set(content.news);
          this.videos.set(content.videos);
          this.courses.set(content.courses);
          this.albums.set(content.albums);
        },
        error: () => this.failed.set(true),
      });
  }

  protected error(kind: ContentKind): string | null {
    return this.errors()[kind] ?? null;
  }

  protected saveNews(): void {
    if (!this.valid(this.newsForm)) return;
    const value = this.newsForm.getRawValue();
    const editing = this.editingNews();
    const request = editing
      ? this.admin.updateNews(editing.id, {
          title: value.title.trim(),
          body: value.body.trim(),
          version: editing.version,
        })
      : this.admin.createNews({ title: value.title.trim(), body: value.body.trim() });
    this.run('news', request, (saved) => {
      this.news.update((items) =>
        editing ? items.map((item) => (item.id === saved.id ? saved : item)) : [saved, ...items],
      );
      this.cancelNewsEdit();
      this.notice.set(editing ? 'Noticia actualizada.' : 'Noticia publicada.');
    });
  }

  protected editNews(item: NewsPost): void {
    this.editingNews.set(item);
    this.newsForm.setValue({ title: item.title, body: item.body });
    this.clearError('news');
  }

  protected cancelNewsEdit(): void {
    this.editingNews.set(null);
    this.newsForm.reset({ title: '', body: '' });
  }

  protected deleteNews(item: NewsPost): void {
    if (!window.confirm(`¿Eliminar la noticia “${item.title}”?`)) return;
    this.remove('news', this.admin.deleteNews(item.id, item.version), () => {
      this.news.update((items) => items.filter((candidate) => candidate.id !== item.id));
      if (this.editingNews()?.id === item.id) this.cancelNewsEdit();
      this.notice.set('Noticia eliminada.');
    });
  }

  protected saveVideo(): void {
    if (!this.valid(this.videoForm)) return;
    const value = this.videoForm.getRawValue();
    const editing = this.editingVideo();
    const request = editing
      ? this.admin.updateVideo(editing.id, {
          title: value.title.trim(),
          url: value.url.trim(),
          version: editing.version,
        })
      : this.admin.createVideo({ title: value.title.trim(), url: value.url.trim() });
    this.run('video', request, (saved) => {
      this.videos.update((items) =>
        editing ? items.map((item) => (item.id === saved.id ? saved : item)) : [...items, saved],
      );
      this.cancelVideoEdit();
      this.notice.set(editing ? 'Vídeo actualizado.' : 'Vídeo publicado.');
    });
  }

  protected editVideo(item: VideoLink): void {
    this.editingVideo.set(item);
    this.videoForm.setValue({ title: item.title, url: item.url });
    this.clearError('video');
  }

  protected cancelVideoEdit(): void {
    this.editingVideo.set(null);
    this.videoForm.reset({ title: '', url: '' });
  }

  protected deleteVideo(item: VideoLink): void {
    if (!window.confirm(`¿Eliminar el vídeo “${item.title}”?`)) return;
    this.remove('video', this.admin.deleteVideo(item.id, item.version), () => {
      this.videos.update((items) => items.filter((candidate) => candidate.id !== item.id));
      if (this.editingVideo()?.id === item.id) this.cancelVideoEdit();
      this.notice.set('Vídeo eliminado.');
    });
  }

  protected saveCourse(): void {
    if (!this.valid(this.courseForm)) return;
    const value = this.courseForm.getRawValue();
    if (value.endDate && value.endDate < value.startDate) {
      this.setError('course', 'La fecha final no puede ser anterior a la fecha de inicio.');
      return;
    }
    const mutation: CourseMutation = {
      title: value.title.trim(),
      description: value.description.trim() || null,
      startDate: value.startDate,
      endDate: value.endDate || null,
      price: value.price,
      instrument: value.instrument.trim(),
      minimumAge: value.minimumAge,
    };
    const editing = this.editingCourse();
    const request = editing
      ? this.admin.updateCourse(editing.id, { ...mutation, version: editing.version })
      : this.admin.createCourse(mutation);
    this.run('course', request, (saved) => {
      this.courses.update((items) =>
        editing ? items.map((item) => (item.id === saved.id ? saved : item)) : [...items, saved],
      );
      this.cancelCourseEdit();
      this.notice.set(editing ? 'Curso actualizado.' : 'Curso publicado.');
    });
  }

  protected editCourse(item: CourseAnnouncement): void {
    this.editingCourse.set(item);
    this.courseForm.setValue({
      title: item.title,
      description: item.description ?? '',
      startDate: item.startDate,
      endDate: item.endDate ?? '',
      price: item.price,
      instrument: item.instrument ?? '',
      minimumAge: item.minimumAge,
    });
    this.clearError('course');
  }

  protected cancelCourseEdit(): void {
    this.editingCourse.set(null);
    this.courseForm.reset({
      title: '',
      description: '',
      startDate: '',
      endDate: '',
      price: 0,
      instrument: '',
      minimumAge: 0,
    });
  }

  protected deleteCourse(item: CourseAnnouncement): void {
    if (!window.confirm(`¿Eliminar el curso “${item.title}”?`)) return;
    this.remove('course', this.admin.deleteCourse(item.id, item.version), () => {
      this.courses.update((items) => items.filter((candidate) => candidate.id !== item.id));
      if (this.editingCourse()?.id === item.id) this.cancelCourseEdit();
      this.notice.set('Curso eliminado.');
    });
  }

  protected saveAlbum(): void {
    if (!this.valid(this.albumForm)) return;
    const value = this.albumForm.getRawValue();
    const mutation = { name: value.name.trim(), description: value.description.trim() || null };
    const editing = this.editingAlbum();
    const request = editing
      ? this.admin.updateAlbum(editing.id, { ...mutation, version: editing.version })
      : this.admin.createAlbum(mutation);
    this.run('album', request, (saved) => {
      this.albums.update((items) =>
        editing ? items.map((item) => (item.id === saved.id ? saved : item)) : [...items, saved],
      );
      this.photoForm.controls.albumId.setValue(saved.id);
      this.cancelAlbumEdit();
      this.notice.set(editing ? 'Álbum actualizado.' : 'Álbum creado.');
    });
  }

  protected editAlbum(item: Album): void {
    this.editingAlbum.set(item);
    this.albumForm.setValue({ name: item.name, description: item.description ?? '' });
    this.clearError('album');
  }

  protected cancelAlbumEdit(): void {
    this.editingAlbum.set(null);
    this.albumForm.reset({ name: '', description: '' });
  }

  protected deleteAlbum(item: Album): void {
    if (!window.confirm(`¿Eliminar el álbum “${item.name}”?`)) return;
    this.remove(
      'album',
      this.admin.deleteAlbum(item.id, item.version),
      () => {
        this.albums.update((items) => items.filter((candidate) => candidate.id !== item.id));
        if (this.editingAlbum()?.id === item.id) this.cancelAlbumEdit();
        this.notice.set('Álbum eliminado.');
      },
      'No se puede eliminar un álbum con fotografías. Elimina primero sus fotografías.',
    );
  }

  protected choosePhoto(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.photoFileInput = input;
    this.photoFile.set(input.files?.item(0) ?? null);
    this.clearError('photo');
  }

  protected uploadPhoto(): void {
    const file = this.photoFile();
    if (!this.valid(this.photoForm) || !file) {
      this.setError('photo', 'Selecciona un álbum y una imagen.');
      return;
    }
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > MAX_FILE_SIZE) {
      this.setError('photo', 'Solo se admiten imágenes PNG o JPEG de hasta 20 MB.');
      return;
    }
    const value = this.photoForm.getRawValue();
    this.run(
      'photo',
      this.admin.uploadPhoto(value.albumId, file, value.caption.trim() || null),
      (created) => {
        this.albums.update((albums) =>
          albums.map((album) =>
            album.id === value.albumId ? { ...album, photos: [...album.photos, created] } : album,
          ),
        );
        this.photoFile.set(null);
        if (this.photoFileInput) this.photoFileInput.value = '';
        this.photoForm.reset({ albumId: value.albumId, caption: '' });
        this.notice.set('Fotografía añadida.');
      },
    );
  }

  protected deletePhoto(album: Album, photo: Photo): void {
    if (!window.confirm('¿Eliminar esta fotografía de forma permanente?')) return;
    this.remove('photo', this.admin.deletePhoto(photo.id), () => {
      this.albums.update((items) =>
        items.map((item) =>
          item.id === album.id
            ? { ...item, photos: item.photos.filter((candidate) => candidate.id !== photo.id) }
            : item,
        ),
      );
      this.notice.set('Fotografía eliminada.');
    });
  }

  private valid(form: { invalid: boolean; markAllAsTouched(): void }): boolean {
    if (!form.invalid) return true;
    form.markAllAsTouched();
    return false;
  }

  private run<T>(kind: ContentKind, request: Observable<T>, success: (value: T) => void): void {
    this.saving.set(kind);
    this.notice.set(null);
    this.clearError(kind);
    request.pipe(finalize(() => this.saving.set(null))).subscribe({
      next: success,
      error: (error: unknown) => this.handleContentError(kind, error),
    });
  }

  private remove(
    kind: ContentKind,
    request: Observable<void>,
    success: () => void,
    conflictMessage?: string,
  ): void {
    this.saving.set(kind);
    this.notice.set(null);
    this.clearError(kind);
    request.pipe(finalize(() => this.saving.set(null))).subscribe({
      next: success,
      error: (error: unknown) => {
        if (isConcurrentModification(error)) {
          this.reloadAfterConflict(kind);
          return;
        }
        const message = adminErrorMessage(error, 'contenido público');
        this.setError(
          kind,
          conflictMessage && message.includes('conflicto') ? conflictMessage : message,
        );
      },
    });
  }

  private handleContentError(kind: ContentKind, error: unknown): void {
    if (isConcurrentModification(error)) {
      this.reloadAfterConflict(kind);
      return;
    }
    this.setError(kind, adminErrorMessage(error, 'contenido público'));
  }

  private reloadAfterConflict(kind: ContentKind): void {
    const editingNewsId = this.editingNews()?.id;
    const editingVideoId = this.editingVideo()?.id;
    const editingCourseId = this.editingCourse()?.id;
    const editingAlbumId = this.editingAlbum()?.id;
    forkJoin({
      news: this.admin.getManagedNews(),
      videos: this.admin.getManagedVideos(),
      courses: this.admin.getManagedCourses(),
      albums: this.admin.getManagedAlbums(),
    }).subscribe({
      next: (content) => {
        this.news.set(content.news);
        this.videos.set(content.videos);
        this.courses.set(content.courses);
        this.albums.set(content.albums);
        this.restoreEditor('news', editingNewsId, content.news, (item) => this.editNews(item));
        this.restoreEditor('video', editingVideoId, content.videos, (item) => this.editVideo(item));
        this.restoreEditor('course', editingCourseId, content.courses, (item) => this.editCourse(item));
        this.restoreEditor('album', editingAlbumId, content.albums, (item) => this.editAlbum(item));
        this.setError(kind, 'Otra persona modificó el contenido. Se han recargado los datos actuales.');
      },
      error: () =>
        this.setError(
          kind,
          'Hay un conflicto de edición y no se ha podido recargar el contenido actual.',
        ),
    });
  }

  private restoreEditor<T extends { id: number }>(
    kind: Exclude<ContentKind, 'photo'>,
    editingId: number | undefined,
    items: T[],
    edit: (item: T) => void,
  ): void {
    if (editingId === undefined) return;
    const latest = items.find((item) => item.id === editingId);
    if (latest) {
      edit(latest);
      return;
    }
    if (kind === 'news') this.cancelNewsEdit();
    if (kind === 'video') this.cancelVideoEdit();
    if (kind === 'course') this.cancelCourseEdit();
    if (kind === 'album') this.cancelAlbumEdit();
  }

  private setError(kind: ContentKind, message: string): void {
    this.errors.update((errors) => ({ ...errors, [kind]: message }));
  }

  private clearError(kind: ContentKind): void {
    this.errors.update((errors) => ({ ...errors, [kind]: undefined }));
  }
}
