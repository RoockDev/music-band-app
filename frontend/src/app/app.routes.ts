import { Routes } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guards';

const privateHome = () =>
  import('./features/private/private-home/private-home-page').then(
    (module) => module.PrivateHomePage,
  );

const privateLayout = () =>
  import('./core/layout/private-layout/private-layout').then((module) => module.PrivateLayout);

const musicianEvents = () =>
  import('./features/private/musician-events/musician-events-page').then(
    (module) => module.MusicianEventsPage,
  );

const musicianEventDetail = () =>
  import('./features/private/musician-event-detail/musician-event-detail-page').then(
    (module) => module.MusicianEventDetailPage,
  );

const musicianLibrary = () =>
  import('./features/private/musician-library/musician-library-page').then(
    (module) => module.MusicianLibraryPage,
  );

export const routes: Routes = [
  {
    path: 'musico',
    canActivate: [authGuard, roleGuard],
    data: { role: 'MUSICIAN' },
    loadComponent: privateLayout,
    children: [
      { path: '', title: 'Área de músico', loadComponent: privateHome },
      { path: 'agenda', title: 'Mi agenda', loadComponent: musicianEvents },
      { path: 'agenda/:id', title: 'Detalle del evento', loadComponent: musicianEventDetail },
      { path: 'biblioteca', title: 'Mis partituras', loadComponent: musicianLibrary },
    ],
  },
  {
    path: 'administracion',
    canActivate: [authGuard, roleGuard],
    data: { role: 'ADMIN' },
    loadComponent: privateLayout,
    children: [{ path: '', title: 'Administración', loadComponent: privateHome }],
  },
  {
    path: '',
    loadComponent: () =>
      import('./core/layout/public-layout/public-layout').then((module) => module.PublicLayout),
    children: [
      {
        path: '',
        title: 'Inicio',
        loadComponent: () =>
          import('./features/public/home/home-page').then((module) => module.HomePage),
      },
      {
        path: 'noticias',
        title: 'Noticias',
        loadComponent: () =>
          import('./features/public/news/news-page').then((module) => module.NewsPage),
      },
      {
        path: 'agenda',
        title: 'Agenda',
        loadComponent: () =>
          import('./features/public/events/events-page').then((module) => module.EventsPage),
      },
      {
        path: 'galeria',
        title: 'Galería',
        loadComponent: () =>
          import('./features/public/gallery/gallery-page').then((module) => module.GalleryPage),
      },
      {
        path: 'videos',
        title: 'Vídeos',
        loadComponent: () =>
          import('./features/public/videos/videos-page').then((module) => module.VideosPage),
      },
      {
        path: 'cursos',
        title: 'Cursos',
        loadComponent: () =>
          import('./features/public/courses/courses-page').then((module) => module.CoursesPage),
      },
      {
        path: 'contacto',
        title: 'Contacto',
        loadComponent: () =>
          import('./features/public/contact/contact-page').then((module) => module.ContactPage),
      },
      {
        path: 'acceso',
        title: 'Acceso',
        canActivate: [guestGuard],
        loadComponent: () =>
          import('./features/auth/login/login-page').then((module) => module.LoginPage),
      },
      {
        path: 'activar',
        title: 'Activar cuenta',
        canActivate: [guestGuard],
        loadComponent: () =>
          import('./features/auth/activate/activate-page').then((module) => module.ActivatePage),
      },
      {
        path: 'restablecer',
        title: 'Restablecer contraseña',
        canActivate: [guestGuard],
        loadComponent: () =>
          import('./features/auth/reset-password/reset-password-page').then(
            (module) => module.ResetPasswordPage,
          ),
      },
      {
        path: '**',
        title: 'Página no encontrada',
        loadComponent: () =>
          import('./features/public/not-found/not-found-page').then(
            (module) => module.NotFoundPage,
          ),
      },
    ],
  },
];
