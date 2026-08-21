export interface NewsPost {
  id: number;
  title: string;
  body: string;
  publishedAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface PublicEvent {
  id: number;
  title: string;
  description: string | null;
  location: string | null;
  startsAt: string;
  status: 'SCHEDULED' | 'CANCELLED';
}

export interface Photo {
  id: number;
  caption: string | null;
  createdAt: string;
}

export interface Album {
  id: number;
  name: string;
  description: string | null;
  photos: Photo[];
  createdAt: string;
  updatedAt: string;
}

export interface VideoLink {
  id: number;
  title: string;
  url: string;
  createdAt: string;
  updatedAt: string;
}

export interface CourseAnnouncement {
  id: number;
  title: string;
  description: string | null;
  startDate: string;
  endDate: string | null;
  price: number;
  instrument: string | null;
  minimumAge: number;
  createdAt: string;
  updatedAt: string;
}

export interface ContactRequest {
  name: string;
  email: string;
  message: string;
}

export interface ContactSubmission extends ContactRequest {
  id: number;
  submittedAt: string;
}
