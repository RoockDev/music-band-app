import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, forkJoin } from 'rxjs';
import { PageState } from '../../../../shared/page-state/page-state';
import { PublicContentService } from '../../../public/data/public-content.service';
import {
  Album,
  CourseAnnouncement,
  NewsPost,
  VideoLink,
} from '../../../public/data/public-content.models';
import { CourseMutation } from '../data/admin.models';
import { adminErrorMessage, AdminService } from '../data/admin.service';

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
  private readonly publicContent = inject(PublicContentService);
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
      news: this.publicContent.getNews(),
      videos: this.publicContent.getVideos(),
      courses: this.publicContent.getCourses(),
      albums: this.publicContent.getGallery(),
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

  protected createNews(): void {
    if (!this.valid(this.newsForm)) return;
    const value = this.newsForm.getRawValue();
    this.run(
      'news',
      this.admin.createNews({ title: value.title.trim(), body: value.body.trim() }),
      (created) => {
        this.news.update((items) => [created, ...items]);
        this.newsForm.reset({ title: '', body: '' });
      },
    );
  }

  protected createVideo(): void {
    if (!this.valid(this.videoForm)) return;
    const value = this.videoForm.getRawValue();
    this.run(
      'video',
      this.admin.createVideo({ title: value.title.trim(), url: value.url.trim() }),
      (created) => {
        this.videos.update((items) => [...items, created]);
        this.videoForm.reset({ title: '', url: '' });
      },
    );
  }

  protected createCourse(): void {
    if (!this.valid(this.courseForm)) return;
    const value = this.courseForm.getRawValue();
    if (value.endDate && value.endDate < value.startDate) {
      this.setError('course', 'La fecha final no puede ser anterior a la fecha de inicio.');
      return;
    }
    const request: CourseMutation = {
      title: value.title.trim(),
      description: value.description.trim() || null,
      startDate: value.startDate,
      endDate: value.endDate || null,
      price: value.price,
      instrument: value.instrument.trim(),
      minimumAge: value.minimumAge,
    };
    this.run('course', this.admin.createCourse(request), (created) => {
      this.courses.update((items) => [...items, created]);
      this.courseForm.reset({
        title: '',
        description: '',
        startDate: '',
        endDate: '',
        price: 0,
        instrument: '',
        minimumAge: 0,
      });
    });
  }

  protected createAlbum(): void {
    if (!this.valid(this.albumForm)) return;
    const value = this.albumForm.getRawValue();
    this.run(
      'album',
      this.admin.createAlbum({
        name: value.name.trim(),
        description: value.description.trim() || null,
      }),
      (created) => {
        this.albums.update((items) => [...items, created]);
        this.photoForm.controls.albumId.setValue(created.id);
        this.albumForm.reset({ name: '', description: '' });
      },
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
      },
    );
  }

  private valid(form: { invalid: boolean; markAllAsTouched(): void }): boolean {
    if (!form.invalid) return true;
    form.markAllAsTouched();
    return false;
  }

  private run<T>(
    kind: ContentKind,
    request: import('rxjs').Observable<T>,
    success: (value: T) => void,
  ): void {
    this.saving.set(kind);
    this.clearError(kind);
    request.pipe(finalize(() => this.saving.set(null))).subscribe({
      next: success,
      error: (error: unknown) => this.setError(kind, adminErrorMessage(error, 'contenido público')),
    });
  }

  private setError(kind: ContentKind, message: string): void {
    this.errors.update((errors) => ({ ...errors, [kind]: message }));
  }

  private clearError(kind: ContentKind): void {
    this.errors.update((errors) => ({ ...errors, [kind]: undefined }));
  }
}
