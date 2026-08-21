import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { PageState } from '../../../../shared/page-state/page-state';
import { UserAccount, UserMutation, UserRole } from '../data/admin.models';
import { adminErrorMessage, AdminService } from '../data/admin.service';

@Component({
  selector: 'app-admin-users-page',
  imports: [DatePipe, PageState, ReactiveFormsModule],
  templateUrl: './admin-users-page.html',
  styleUrl: './admin-users-page.scss',
})
export class AdminUsersPage implements OnInit {
  private readonly admin = inject(AdminService);
  private readonly formBuilder = inject(FormBuilder);
  protected readonly session = inject(AuthService).session;
  protected readonly users = signal<UserAccount[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly saving = signal(false);
  protected readonly actionId = signal<number | null>(null);
  protected readonly editingId = signal<number | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly activationLink = signal<string | null>(null);
  protected readonly userForm = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    role: this.formBuilder.nonNullable.control<UserRole>('MUSICIAN'),
    minor: false,
    guardianContact: '',
    consentOnFile: false,
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.admin
      .getUsers()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (users) => this.users.set([...users].sort((a, b) => a.email.localeCompare(b.email))),
        error: () => this.failed.set(true),
      });
  }

  protected edit(user: UserAccount): void {
    this.editingId.set(user.id);
    this.activationLink.set(null);
    this.formError.set(null);
    this.userForm.setValue({
      email: user.email,
      role: user.role,
      minor: user.minor,
      guardianContact: user.guardianContact ?? '',
      consentOnFile: user.consentOnFile,
    });
  }

  protected resetForm(): void {
    this.editingId.set(null);
    this.formError.set(null);
    this.userForm.reset({
      email: '',
      role: 'MUSICIAN',
      minor: false,
      guardianContact: '',
      consentOnFile: false,
    });
  }

  protected submit(): void {
    this.formError.set(null);
    this.activationLink.set(null);
    if (this.userForm.invalid) {
      this.userForm.markAllAsTouched();
      return;
    }

    const value = this.userForm.getRawValue();
    if (value.minor && (!value.guardianContact.trim() || !value.consentOnFile)) {
      this.formError.set('Las cuentas de menores requieren contacto responsable y consentimiento.');
      return;
    }

    const payload: UserMutation = {
      email: value.email.trim(),
      role: value.role,
      minor: value.minor,
      guardianContact: value.minor ? value.guardianContact.trim() : null,
      consentOnFile: value.minor && value.consentOnFile,
    };
    const editingId = this.editingId();
    this.saving.set(true);

    if (editingId === null) {
      this.admin
        .createUser(payload)
        .pipe(finalize(() => this.saving.set(false)))
        .subscribe({
          next: (result) => {
            this.users.update((users) =>
              [...users, result.user].sort((a, b) => a.email.localeCompare(b.email)),
            );
            const activationUrl = new URL('/activar', window.location.origin);
            activationUrl.searchParams.set('token', result.activationToken);
            this.activationLink.set(activationUrl.toString());
            this.resetForm();
          },
          error: (error: unknown) => this.formError.set(adminErrorMessage(error, 'usuarios')),
        });
      return;
    }

    this.admin
      .updateUser(editingId, payload)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (updated) => {
          this.users.update((users) =>
            users.map((user) => (user.id === updated.id ? updated : user)),
          );
          this.resetForm();
        },
        error: (error: unknown) => this.formError.set(adminErrorMessage(error, 'usuarios')),
      });
  }

  protected deactivate(user: UserAccount): void {
    const confirmed = window.confirm(
      `¿Desactivar la cuenta ${user.email}? La persona dejará de poder iniciar sesión.`,
    );
    if (!confirmed) {
      return;
    }

    this.actionId.set(user.id);
    this.actionError.set(null);
    this.admin
      .deactivateUser(user.id)
      .pipe(finalize(() => this.actionId.set(null)))
      .subscribe({
        next: () =>
          this.users.update((users) =>
            users.map((item) =>
              item.id === user.id ? { ...item, status: 'DEACTIVATED' as const } : item,
            ),
          ),
        error: (error: unknown) => this.actionError.set(adminErrorMessage(error, 'usuarios')),
      });
  }
}
