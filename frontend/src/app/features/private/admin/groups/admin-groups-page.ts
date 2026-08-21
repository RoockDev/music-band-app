import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { PageState } from '../../../../shared/page-state/page-state';
import { Group, GroupMember, GroupMutation } from '../data/admin.models';
import { adminErrorMessage, AdminService } from '../data/admin.service';

@Component({
  selector: 'app-admin-groups-page',
  imports: [PageState, ReactiveFormsModule],
  templateUrl: './admin-groups-page.html',
  styleUrl: './admin-groups-page.scss',
})
export class AdminGroupsPage implements OnInit {
  private readonly admin = inject(AdminService);
  private readonly formBuilder = inject(FormBuilder);
  protected readonly groups = signal<Group[]>([]);
  protected readonly members = signal<GroupMember[]>([]);
  protected readonly selectedGroup = signal<Group | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly membersLoading = signal(false);
  protected readonly membersFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly groupActionId = signal<number | null>(null);
  protected readonly memberActionId = signal<number | null>(null);
  protected readonly editingId = signal<number | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly memberError = signal<string | null>(null);
  protected readonly groupForm = this.formBuilder.nonNullable.group({
    name: ['', Validators.required],
    description: '',
  });
  protected readonly memberForm = this.formBuilder.nonNullable.group({
    musicianId: [0, [Validators.required, Validators.min(1)]],
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.admin
      .getGroups()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (groups) => this.groups.set([...groups].sort((a, b) => a.name.localeCompare(b.name))),
        error: () => this.failed.set(true),
      });
  }

  protected edit(group: Group): void {
    this.editingId.set(group.id);
    this.formError.set(null);
    this.groupForm.setValue({ name: group.name, description: group.description ?? '' });
  }

  protected resetForm(): void {
    this.editingId.set(null);
    this.formError.set(null);
    this.groupForm.reset({ name: '', description: '' });
  }

  protected submit(): void {
    this.formError.set(null);
    if (this.groupForm.invalid) {
      this.groupForm.markAllAsTouched();
      return;
    }

    const value = this.groupForm.getRawValue();
    const payload: GroupMutation = {
      name: value.name.trim(),
      description: value.description.trim() || null,
    };
    const editingId = this.editingId();
    this.saving.set(true);

    if (editingId === null) {
      this.admin
        .createGroup(payload)
        .pipe(finalize(() => this.saving.set(false)))
        .subscribe({
          next: (group) => {
            this.groups.update((groups) =>
              [...groups, group].sort((a, b) => a.name.localeCompare(b.name)),
            );
            this.resetForm();
          },
          error: (error: unknown) => this.formError.set(adminErrorMessage(error, 'grupos')),
        });
      return;
    }

    this.admin
      .updateGroup(editingId, payload)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (updated) => {
          this.groups.update((groups) =>
            groups.map((group) => (group.id === updated.id ? updated : group)),
          );
          if (this.selectedGroup()?.id === updated.id) {
            this.selectedGroup.set(updated);
          }
          this.resetForm();
        },
        error: (error: unknown) => this.formError.set(adminErrorMessage(error, 'grupos')),
      });
  }

  protected remove(group: Group): void {
    if (!window.confirm(`¿Eliminar el grupo ${group.name}?`)) {
      return;
    }

    this.groupActionId.set(group.id);
    this.actionError.set(null);
    this.admin
      .deleteGroup(group.id)
      .pipe(finalize(() => this.groupActionId.set(null)))
      .subscribe({
        next: () => {
          this.groups.update((groups) => groups.filter((item) => item.id !== group.id));
          if (this.selectedGroup()?.id === group.id) {
            this.closeMembers();
          }
        },
        error: (error: unknown) => {
          if ((error as { status?: number }).status === 409) {
            this.actionError.set('Retira a todos los músicos del grupo antes de eliminarlo.');
          } else {
            this.actionError.set(adminErrorMessage(error, 'grupos'));
          }
        },
      });
  }

  protected manageMembers(group: Group): void {
    this.selectedGroup.set(group);
    this.members.set([]);
    this.loadMembers(group.id);
  }

  protected closeMembers(): void {
    this.selectedGroup.set(null);
    this.members.set([]);
    this.memberError.set(null);
    this.memberForm.reset({ musicianId: 0 });
  }

  protected loadMembers(groupId: number): void {
    this.membersLoading.set(true);
    this.membersFailed.set(false);
    this.admin
      .getGroupMembers(groupId)
      .pipe(finalize(() => this.membersLoading.set(false)))
      .subscribe({
        next: (members) =>
          this.members.set([...members].sort((a, b) => a.email.localeCompare(b.email))),
        error: () => this.membersFailed.set(true),
      });
  }

  protected assign(): void {
    const group = this.selectedGroup();
    if (!group || this.memberForm.invalid) {
      this.memberForm.markAllAsTouched();
      return;
    }

    const musicianId = this.memberForm.controls.musicianId.value;
    this.memberActionId.set(musicianId);
    this.memberError.set(null);
    this.admin
      .assignMusician(group.id, musicianId)
      .pipe(finalize(() => this.memberActionId.set(null)))
      .subscribe({
        next: () => {
          this.memberForm.reset({ musicianId: 0 });
          this.loadMembers(group.id);
        },
        error: (error: unknown) =>
          this.memberError.set(adminErrorMessage(error, 'miembros de grupos')),
      });
  }

  protected unassign(member: GroupMember): void {
    const group = this.selectedGroup();
    if (!group) {
      return;
    }

    this.memberActionId.set(member.id);
    this.memberError.set(null);
    this.admin
      .unassignMusician(group.id, member.id)
      .pipe(finalize(() => this.memberActionId.set(null)))
      .subscribe({
        next: () =>
          this.members.update((members) => members.filter((item) => item.id !== member.id)),
        error: (error: unknown) =>
          this.memberError.set(adminErrorMessage(error, 'miembros de grupos')),
      });
  }
}
