import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, forkJoin } from 'rxjs';
import { PageState } from '../../../../shared/page-state/page-state';
import { SheetMusic } from '../../data/private-content.models';
import { Collection, SheetMusicUpdate, SheetMusicUpload } from '../data/admin.models';
import { parseIdList } from '../data/admin-form.utils';
import {
  adminErrorMessage,
  AdminService,
  isConcurrentModification,
} from '../data/admin.service';

const MAX_FILE_SIZE = 20 * 1024 * 1024;
const ALLOWED_FILE_TYPES = ['application/pdf', 'image/png', 'image/jpeg'];

@Component({
  selector: 'app-admin-archive-page',
  imports: [PageState, ReactiveFormsModule],
  templateUrl: './admin-archive-page.html',
  styleUrl: './admin-archive-page.scss',
})
export class AdminArchivePage implements OnInit {
  private readonly admin = inject(AdminService);
  private readonly formBuilder = inject(FormBuilder);
  private fileInput: HTMLInputElement | null = null;
  protected readonly collections = signal<Collection[]>([]);
  protected readonly scores = signal<SheetMusic[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly collectionSaving = signal(false);
  protected readonly uploadSaving = signal(false);
  protected readonly scoreActionId = signal<number | null>(null);
  protected readonly collectionError = signal<string | null>(null);
  protected readonly collectionNotice = signal<string | null>(null);
  protected readonly editingCollection = signal<Collection | null>(null);
  protected readonly uploadError = signal<string | null>(null);
  protected readonly scoreNotice = signal<string | null>(null);
  protected readonly editingScore = signal<SheetMusic | null>(null);
  protected readonly selectedFile = signal<File | null>(null);
  protected readonly collectionForm = this.formBuilder.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    description: ['', Validators.maxLength(255)],
  });
  protected readonly uploadForm = this.formBuilder.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(255)]],
    composer: ['', Validators.maxLength(255)],
    collectionId: [0, Validators.min(1)],
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
    forkJoin({
      collections: this.admin.getCollections(),
      scores: this.admin.getManagedSheetMusic(),
    })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: ({ collections, scores }) => {
          this.collections.set(this.sortCollections(collections));
          this.scores.set(this.sortScores(scores));
        },
        error: () => this.failed.set(true),
      });
  }

  protected saveCollection(): void {
    this.collectionError.set(null);
    this.collectionNotice.set(null);
    if (this.collectionForm.invalid) {
      this.collectionForm.markAllAsTouched();
      return;
    }
    const value = this.collectionForm.getRawValue();
    const editing = this.editingCollection();
    this.collectionSaving.set(true);
    const request = editing
      ? this.admin.updateCollection(editing.id, {
          name: value.name.trim(),
          description: value.description.trim() || null,
          version: editing.version,
        })
      : this.admin.createCollection({
          name: value.name.trim(),
          description: value.description.trim() || null,
        });
    request.pipe(finalize(() => this.collectionSaving.set(false))).subscribe({
      next: (saved) => {
        this.collections.update((items) =>
          this.sortCollections(
            editing
              ? items.map((item) => (item.id === saved.id ? saved : item))
              : [...items, saved],
          ),
        );
        this.uploadForm.controls.collectionId.setValue(saved.id);
        this.cancelCollectionEdit();
        this.collectionNotice.set(editing ? 'Colección actualizada.' : 'Colección creada.');
      },
      error: (error: unknown) => this.handleCollectionError(error, editing?.id),
    });
  }

  protected editCollection(collection: Collection): void {
    this.editingCollection.set(collection);
    this.collectionForm.setValue({
      name: collection.name,
      description: collection.description ?? '',
    });
    this.collectionError.set(null);
  }

  protected cancelCollectionEdit(): void {
    this.editingCollection.set(null);
    this.collectionForm.reset({ name: '', description: '' });
  }

  protected collectionScoreCount(collectionId: number): number {
    return this.scores().filter((score) => score.collectionId === collectionId).length;
  }

  protected deleteCollection(collection: Collection): void {
    if (this.collectionScoreCount(collection.id) > 0) {
      this.collectionError.set(
        'No se puede eliminar una colección que contiene partituras. Reubica o elimina primero su contenido.',
      );
      return;
    }
    if (!window.confirm(`¿Eliminar la colección “${collection.name}”?`)) return;
    this.collectionSaving.set(true);
    this.collectionError.set(null);
    this.collectionNotice.set(null);
    this.admin
      .deleteCollection(collection.id, collection.version)
      .pipe(finalize(() => this.collectionSaving.set(false)))
      .subscribe({
        next: () => {
          this.collections.update((items) => items.filter((item) => item.id !== collection.id));
          if (this.editingCollection()?.id === collection.id) this.cancelCollectionEdit();
          this.collectionNotice.set('Colección eliminada.');
        },
        error: (error: unknown) => this.handleCollectionError(error, collection.id),
      });
  }

  protected chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.fileInput = input;
    this.selectedFile.set(input.files?.item(0) ?? null);
    this.uploadError.set(null);
  }

  protected saveScore(): void {
    if (this.editingScore()) {
      this.updateScore();
      return;
    }
    this.upload();
  }

  protected editScore(score: SheetMusic): void {
    this.editingScore.set(score);
    this.selectedFile.set(null);
    if (this.fileInput) this.fileInput.value = '';
    this.uploadError.set(null);
    this.scoreNotice.set(null);
    this.uploadForm.setValue({
      title: score.title,
      composer: score.composer ?? '',
      collectionId: score.collectionId,
      allScope: score.allScope,
      groupIds: score.groupIds.join(', '),
      musicianIds: score.musicianIds.join(', '),
    });
  }

  protected cancelScoreEdit(): void {
    const collectionId = this.uploadForm.controls.collectionId.value;
    this.editingScore.set(null);
    this.uploadError.set(null);
    this.uploadForm.reset({
      title: '',
      composer: '',
      collectionId,
      allScope: false,
      groupIds: '',
      musicianIds: '',
    });
  }

  protected globalScopeChanged(): void {
    if (this.uploadForm.controls.allScope.value) {
      this.uploadForm.patchValue({ groupIds: '', musicianIds: '' });
    }
  }

  protected upload(): void {
    this.uploadError.set(null);
    this.scoreNotice.set(null);
    const file = this.selectedFile();
    if (this.uploadForm.invalid || !file) {
      this.uploadForm.markAllAsTouched();
      this.uploadError.set('Completa los datos y selecciona un archivo.');
      return;
    }
    if (!ALLOWED_FILE_TYPES.includes(file.type) || file.size > MAX_FILE_SIZE) {
      this.uploadError.set('Solo se admiten PDF, PNG o JPEG de hasta 20 MB.');
      return;
    }
    const value = this.uploadForm.getRawValue();
    const groupIds = parseIdList(value.groupIds);
    const musicianIds = parseIdList(value.musicianIds);
    if (groupIds === null || musicianIds === null) {
      this.uploadError.set(
        'Los alcances deben contener identificadores positivos separados por comas.',
      );
      return;
    }
    const request: SheetMusicUpload = {
      title: value.title.trim(),
      composer: value.composer.trim() || null,
      collectionId: value.collectionId,
      allScope: value.allScope,
      groupIds,
      musicianIds,
      file,
    };
    this.uploadSaving.set(true);
    this.admin
      .uploadSheetMusic(request)
      .pipe(finalize(() => this.uploadSaving.set(false)))
      .subscribe({
        next: (created) => {
          this.scores.update((items) => this.sortScores([...items, created]));
          this.selectedFile.set(null);
          if (this.fileInput) {
            this.fileInput.value = '';
          }
          this.uploadForm.reset({
            title: '',
            composer: '',
            collectionId: value.collectionId,
            allScope: false,
            groupIds: '',
            musicianIds: '',
          });
          this.scoreNotice.set('Partitura subida.');
        },
        error: (error: unknown) =>
          this.uploadError.set(adminErrorMessage(error, 'el archivo de partituras')),
      });
  }

  protected updateScore(): void {
    const editing = this.editingScore();
    if (!editing || this.uploadForm.invalid) {
      this.uploadForm.markAllAsTouched();
      return;
    }
    this.uploadError.set(null);
    this.scoreNotice.set(null);
    const value = this.uploadForm.getRawValue();
    const groupIds = parseIdList(value.groupIds);
    const musicianIds = parseIdList(value.musicianIds);
    if (groupIds === null || musicianIds === null) {
      this.uploadError.set(
        'Los alcances deben contener identificadores positivos separados por comas.',
      );
      return;
    }
    const request: SheetMusicUpdate = {
      title: value.title.trim(),
      composer: value.composer.trim() || null,
      collectionId: value.collectionId,
      allScope: value.allScope,
      groupIds,
      musicianIds,
      version: editing.version,
    };
    this.uploadSaving.set(true);
    this.admin
      .updateSheetMusic(editing.id, request)
      .pipe(finalize(() => this.uploadSaving.set(false)))
      .subscribe({
        next: (updated) => {
          this.scores.update((items) =>
            this.sortScores(items.map((item) => (item.id === updated.id ? updated : item))),
          );
          this.cancelScoreEdit();
          this.scoreNotice.set('Partitura actualizada.');
        },
        error: (error: unknown) => this.handleScoreError(error, editing.id),
      });
  }

  protected deleteScore(score: SheetMusic): void {
    if (!window.confirm(`¿Eliminar la partitura “${score.title}” y su archivo?`)) return;
    this.scoreActionId.set(score.id);
    this.uploadError.set(null);
    this.scoreNotice.set(null);
    this.admin
      .deleteSheetMusic(score.id, score.version)
      .pipe(finalize(() => this.scoreActionId.set(null)))
      .subscribe({
        next: () => {
          this.scores.update((items) => items.filter((item) => item.id !== score.id));
          if (this.editingScore()?.id === score.id) this.cancelScoreEdit();
          this.scoreNotice.set('Partitura eliminada.');
        },
        error: (error: unknown) => this.handleScoreError(error, score.id),
      });
  }

  protected collectionName(id: number): string {
    return this.collections().find((item) => item.id === id)?.name ?? `Colección ${id}`;
  }

  private handleCollectionError(error: unknown, collectionId?: number): void {
    if (!isConcurrentModification(error)) {
      this.collectionError.set(adminErrorMessage(error, 'el archivo de partituras'));
      return;
    }

    forkJoin({
      collections: this.admin.getCollections(),
      scores: this.admin.getManagedSheetMusic(),
    }).subscribe({
      next: ({ collections, scores }) => {
        const sorted = this.sortCollections(collections);
        this.collections.set(sorted);
        this.scores.set(this.sortScores(scores));
        const latest = sorted.find((item) => item.id === collectionId);
        if (latest && this.editingCollection()?.id === collectionId) {
          this.editCollection(latest);
        } else if (!latest && this.editingCollection()?.id === collectionId) {
          this.cancelCollectionEdit();
        }
        this.collectionError.set(
          'Otra persona modificó la colección. Se han recargado los datos actuales.',
        );
      },
      error: () =>
        this.collectionError.set(
          'Hay un conflicto de edición y no se ha podido recargar el archivo actual.',
        ),
    });
  }

  private handleScoreError(error: unknown, scoreId: number): void {
    if (!isConcurrentModification(error)) {
      this.uploadError.set(adminErrorMessage(error, 'partituras'));
      return;
    }
    forkJoin({
      collections: this.admin.getCollections(),
      scores: this.admin.getManagedSheetMusic(),
    }).subscribe({
      next: ({ collections, scores }) => {
        this.collections.set(this.sortCollections(collections));
        const sortedScores = this.sortScores(scores);
        this.scores.set(sortedScores);
        const latest = sortedScores.find((item) => item.id === scoreId);
        if (latest && this.editingScore()?.id === scoreId) {
          this.editScore(latest);
        } else if (!latest && this.editingScore()?.id === scoreId) {
          this.cancelScoreEdit();
        }
        this.uploadError.set(
          'Otra persona modificó la partitura. Se han recargado los datos actuales.',
        );
      },
      error: () =>
        this.uploadError.set(
          'Hay un conflicto de edición y no se ha podido recargar el archivo actual.',
        ),
    });
  }

  private sortCollections(items: Collection[]): Collection[] {
    return [...items].sort((a, b) => a.name.localeCompare(b.name));
  }
  private sortScores(items: SheetMusic[]): SheetMusic[] {
    return [...items].sort((a, b) => a.title.localeCompare(b.title));
  }
}
