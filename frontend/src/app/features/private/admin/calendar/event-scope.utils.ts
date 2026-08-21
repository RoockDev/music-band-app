import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

export interface EventScopeSelection {
  allScope: boolean;
  groupIds: number[];
  musicianIds: number[];
}

export function deduplicateIds(ids: number[]): number[] {
  return [...new Set(ids)];
}

export const eventScopeValidator: ValidatorFn = (
  control: AbstractControl,
): ValidationErrors | null => {
  const allScope = control.get('allScope')?.value === true;
  const groupIds = (control.get('groupIds')?.value ?? []) as number[];
  const musicianIds = (control.get('musicianIds')?.value ?? []) as number[];
  return allScope && (groupIds.length > 0 || musicianIds.length > 0)
    ? { ambiguousScope: true }
    : null;
};

export function scopeRemovesRecipients(
  before: EventScopeSelection,
  after: EventScopeSelection,
): boolean {
  if (before.allScope) {
    return !after.allScope;
  }
  if (after.allScope) {
    return false;
  }
  return (
    before.groupIds.some((id) => !after.groupIds.includes(id)) ||
    before.musicianIds.some((id) => !after.musicianIds.includes(id))
  );
}

export function describeScope(scope: EventScopeSelection): string {
  if (scope.allScope) {
    return 'Toda la banda tendrá acceso interno.';
  }
  if (scope.groupIds.length === 0 && scope.musicianIds.length === 0) {
    return 'Nadie tendrá acceso interno hasta que se añada un grupo o músico.';
  }
  return `${scope.groupIds.length} grupo(s) y ${scope.musicianIds.length} músico(s) tendrán acceso directo.`;
}

export function describeScopeImpact(
  before: EventScopeSelection | null,
  after: EventScopeSelection,
): string {
  const result = describeScope(after);
  if (before === null) {
    return result;
  }
  const removed =
    before.allScope !== after.allScope ||
    before.groupIds.some((id) => !after.groupIds.includes(id)) ||
    before.musicianIds.some((id) => !after.musicianIds.includes(id));
  const added =
    before.allScope !== after.allScope ||
    after.groupIds.some((id) => !before.groupIds.includes(id)) ||
    after.musicianIds.some((id) => !before.musicianIds.includes(id));
  if (!removed && !added) {
    return `Sin cambios de alcance. ${result}`;
  }
  return `El alcance cambiará. ${result}`;
}
