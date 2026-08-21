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
