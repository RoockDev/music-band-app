export type UserRole = 'ADMIN' | 'MUSICIAN';
export type UserStatus = 'PENDING' | 'ACTIVE' | 'DEACTIVATED';

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
}

export interface UserMutation {
  email: string;
  role: UserRole;
  minor: boolean;
  guardianContact: string | null;
  consentOnFile: boolean;
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
}

export interface GroupMutation {
  name: string;
  description: string | null;
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

export type EventUpdateMutation = Omit<EventCreateMutation, 'groupIds' | 'musicianIds'>;

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
}
