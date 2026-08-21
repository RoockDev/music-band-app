import { DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { PageState } from '../../../../shared/page-state/page-state';
import { AdminPermission, UserAccount, UserMutation, UserRole } from '../data/admin.models';
import {
  adminErrorMessage,
  AdminService,
  isConcurrentModification,
} from '../data/admin.service';

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
  protected readonly editingUser = signal<UserAccount | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly activationLink = signal<string | null>(null);
  protected readonly permissionUserId = signal<number | null>(null);
  protected readonly permissions = signal<ReadonlySet<AdminPermission>>(new Set());
  protected readonly permissionsLoading = signal(false);
  protected readonly permissionAction = signal<AdminPermission | null>(null);
  protected readonly permissionError = signal<string | null>(null);
  protected readonly permissionFeedback = signal<string | null>(null);
  protected readonly permissionOptions: ReadonlyArray<{
    value: AdminPermission;
    label: string;
    description: string;
  }> = [
    {
      value: 'MANAGE_USERS',
      label: 'Gestionar usuarios',
      description: 'Crear, editar y desactivar cuentas de músicos.',
    },
    {
      value: 'MANAGE_ADMIN_ROLES',
      label: 'Gestionar administradores y permisos',
      description: 'Crear cuentas administrativas, cambiar roles y asignar estos permisos.',
    },
    {
      value: 'MANAGE_GROUPS',
      label: 'Gestionar agrupaciones',
      description: 'Crear agrupaciones y administrar sus integrantes.',
    },
    {
      value: 'MANAGE_SHEET_MUSIC',
      label: 'Gestionar partituras',
      description: 'Crear colecciones y publicar partituras.',
    },
    {
      value: 'MANAGE_EVENTS',
      label: 'Gestionar agenda',
      description: 'Crear, editar y cancelar eventos.',
    },
    {
      value: 'MANAGE_CONTENT',
      label: 'Gestionar contenido público',
      description: 'Publicar noticias, vídeos, cursos, álbumes y fotografías.',
    },
  ];
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
    this.editingUser.set(user);
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
    this.editingUser.set(null);
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
    const editingUser = this.editingUser();
    const editingId = editingUser?.id ?? null;
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
      .updateUser(editingId, { ...payload, version: editingUser!.version })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (updated) => {
          this.users.update((users) =>
            users.map((user) => (user.id === updated.id ? updated : user)),
          );
          if (updated.role !== 'ADMIN' && this.permissionUserId() === updated.id) {
            this.closePermissions();
          }
          this.resetForm();
        },
        error: (error: unknown) => this.handleUpdateError(error, editingId),
      });
  }

  private handleUpdateError(error: unknown, userId: number): void {
    if (!isConcurrentModification(error)) {
      this.formError.set(adminErrorMessage(error, 'usuarios'));
      return;
    }

    this.admin.getUsers().subscribe({
      next: (users) => {
        const sorted = [...users].sort((a, b) => a.email.localeCompare(b.email));
        this.users.set(sorted);
        const latest = sorted.find((user) => user.id === userId);
        if (latest) {
          this.edit(latest);
          this.formError.set('Otra persona modificó la cuenta. Se han recargado sus datos actuales.');
        } else {
          this.resetForm();
          this.formError.set('La cuenta ya no existe o no está disponible.');
        }
      },
      error: () =>
        this.formError.set(
          'Hay un conflicto de edición y no se han podido recargar las cuentas actuales.',
        ),
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

  protected openPermissions(user: UserAccount): void {
    if (user.role !== 'ADMIN') {
      return;
    }
    this.permissionUserId.set(user.id);
    this.permissions.set(new Set());
    this.permissionError.set(null);
    this.permissionFeedback.set(null);
    this.permissionsLoading.set(true);
    this.admin
      .getAdminPermissions(user.id)
      .pipe(finalize(() => this.permissionsLoading.set(false)))
      .subscribe({
        next: (response) => this.permissions.set(new Set(response.permissions)),
        error: (error: unknown) =>
          this.permissionError.set(adminErrorMessage(error, 'permisos administrativos')),
      });
  }

  protected closePermissions(): void {
    this.permissionUserId.set(null);
    this.permissions.set(new Set());
    this.permissionError.set(null);
    this.permissionFeedback.set(null);
  }

  protected hasPermission(permission: AdminPermission): boolean {
    return this.permissions().has(permission);
  }

  protected changePermission(user: UserAccount, permission: AdminPermission): void {
    const granted = this.hasPermission(permission);
    const option = this.permissionOptions.find((item) => item.value === permission)!;
    const verb = granted ? 'revocar' : 'conceder';
    if (!window.confirm(`¿Quieres ${verb} “${option.label}” a ${user.email}?`)) {
      return;
    }

    this.permissionAction.set(permission);
    this.permissionError.set(null);
    this.permissionFeedback.set(null);
    const request = granted
      ? this.admin.revokeAdminPermission(user.id, permission)
      : this.admin.grantAdminPermission(user.id, permission);
    request.pipe(finalize(() => this.permissionAction.set(null))).subscribe({
      next: (response) => {
        this.permissions.set(new Set(response.permissions));
        this.permissionFeedback.set(
          granted ? 'Permiso revocado correctamente.' : 'Permiso concedido correctamente.',
        );
      },
      error: (error: unknown) =>
        this.permissionError.set(adminErrorMessage(error, 'permisos administrativos')),
    });
  }
}
