export type EventStatus = 'SCHEDULED' | 'CANCELLED';

export interface InternalEvent {
  id: number;
  title: string;
  description: string | null;
  location: string | null;
  startsAt: string;
  isPublic: boolean;
  allScope: boolean;
  status: EventStatus;
  createdAt: string;
  updatedAt: string;
}

export interface SheetMusic {
  id: number;
  title: string;
  composer: string | null;
  collectionId: number;
  allScope: boolean;
  groupIds: number[];
  musicianIds: number[];
  originalFilename: string | null;
  contentType: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}
