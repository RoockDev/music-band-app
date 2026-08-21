import { Routes } from '@angular/router';

export const routes: Routes = [
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
        loadComponent: () => import('./features/public/news/news-page').then((module) => module.NewsPage),
      },
      {
        path: 'agenda',
        title: 'Agenda',
        loadComponent: () => import('./features/public/events/events-page').then((module) => module.EventsPage),
      },
      {
        path: 'galeria',
        title: 'Galería',
        loadComponent: () => import('./features/public/gallery/gallery-page').then((module) => module.GalleryPage),
      },
      {
        path: 'videos',
        title: 'Vídeos',
        loadComponent: () => import('./features/public/videos/videos-page').then((module) => module.VideosPage),
      },
      {
        path: 'cursos',
        title: 'Cursos',
        loadComponent: () => import('./features/public/courses/courses-page').then((module) => module.CoursesPage),
      },
      {
        path: 'contacto',
        title: 'Contacto',
        loadComponent: () => import('./features/public/contact/contact-page').then((module) => module.ContactPage),
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
