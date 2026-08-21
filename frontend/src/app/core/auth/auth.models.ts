export type UserRole = 'ADMIN' | 'MUSICIAN';

export interface CurrentUser {
  id: number;
  email: string;
  role: UserRole;
}

export interface LoginCredentials {
  email: string;
  password: string;
}

export interface PasswordCompletion {
  token: string;
  newPassword: string;
}

export interface PasswordResetRequest {
  email: string;
}

export function landingPath(role: UserRole): string {
  return role === 'ADMIN' ? '/administracion' : '/musico';
}
