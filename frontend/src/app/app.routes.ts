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
