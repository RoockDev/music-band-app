import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { PageState } from '../../../../shared/page-state/page-state';
import { AuditLog } from '../data/admin.models';
import { adminErrorMessage, AdminService } from '../data/admin.service';

@Component({
  selector: 'app-admin-audit-page',
  imports: [DatePipe, PageState, ReactiveFormsModule],
  templateUrl: './admin-audit-page.html',
  styleUrl: './admin-audit-page.scss',
})
export class AdminAuditPage {
  private readonly admin = inject(AdminService);
  private readonly formBuilder = inject(FormBuilder);
  protected readonly entries = signal<AuditLog[]>([]);
  protected readonly loading = signal(false);
  protected readonly searched = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly auditForm = this.formBuilder.nonNullable.group({
    entityType: ['UserAccount', Validators.required],
    entityId: [0, Validators.min(1)],
  });

  protected search(): void {
    this.error.set(null);
    if (this.auditForm.invalid) {
      this.auditForm.markAllAsTouched();
      return;
    }
    const value = this.auditForm.getRawValue();
    this.loading.set(true);
    this.searched.set(true);
    this.admin
      .getAuditHistory(value.entityType, value.entityId)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (entries) => this.entries.set(entries),
        error: (error: unknown) => {
          this.entries.set([]);
          this.error.set(adminErrorMessage(error, 'la auditoría'));
        },
      });
  }
}
