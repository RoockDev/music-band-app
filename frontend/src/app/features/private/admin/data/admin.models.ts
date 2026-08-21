import { InternalEvent } from '../../data/private-content.models';

export type UserRole = 'ADMIN' | 'MUSICIAN';
export type UserStatus = 'PENDING' | 'ACTIVE' | 'DEACTIVATED';
export type AdminPermission =
  | 'MANAGE_USERS'
  | 'MANAGE_ADMIN_ROLES'
  | 'MANAGE_GROUPS'
  | 'MANAGE_SHEET_MUSIC'
  | 'MANAGE_EVENTS'
  | 'MANAGE_CONTENT';

export interface AdminPermissions {
  userId: number;
  permissions: AdminPermission[];
}

export interface UserAccount {
  id: number;
  email: string;
  role: UserRole;
  status: UserStatus;
  minor: boolean;
  guardianContact: string | null;
  consentOnFile: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface UserMutation {
  email: string;
  role: UserRole;
  minor: boolean;
  guardianContact: string | null;
  consentOnFile: boolean;
}

export interface UserUpdateMutation extends UserMutation {
  version: number;
}

export interface CreateUserResult {
  user: UserAccount;
  activationToken: string;
}

export interface Group {
  id: number;
  name: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface GroupMutation {
  name: string;
  description: string | null;
}

export interface GroupUpdateMutation extends GroupMutation {
  version: number;
}

export interface GroupMember {
  id: number;
  email: string;
  role: UserRole;
}

export interface Collection {
  id: number;
  name: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface CollectionMutation {
  name: string;
  description: string | null;
  version?: number;
}

export interface EventCreateMutation {
  title: string;
  description: string | null;
  location: string | null;
  startsAt: string;
  isPublic: boolean;
  allScope: boolean;
  groupIds: number[];
  musicianIds: number[];
}

export interface EventUpdateMutation extends EventCreateMutation {
  version: number;
}

export interface ManagedEvent extends InternalEvent {
  version: number;
  groupIds: number[];
  musicianIds: number[];
}

export interface EventCancellation {
  id: number;
  status: InternalEvent['status'];
  updatedAt: string;
  version: number;
}

export interface EventTarget {
  id: number;
  label: string;
}

export interface EventTargetCatalog {
  groups: EventTarget[];
  musicians: EventTarget[];
}

export interface SheetMusicUpload {
  title: string;
  composer: string | null;
  collectionId: number;
  allScope: boolean;
  groupIds: number[];
  musicianIds: number[];
  file: File;
}

export interface AuditLog {
  id: number;
  actorId: number;
  action: string;
  entityType: string;
  entityId: number;
  timestamp: string;
  details: string | null;
}

export interface CourseMutation {
  title: string;
  description: string | null;
  startDate: string;
  endDate: string | null;
  price: number;
  instrument: string;
  minimumAge: number;
  version?: number;
}
