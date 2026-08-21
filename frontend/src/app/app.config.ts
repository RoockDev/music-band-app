import { provideHttpClient } from '@angular/common/http';
import localeEs from '@angular/common/locales/es';
import { registerLocaleData } from '@angular/common';
import {
  ApplicationConfig,
  inject,
  LOCALE_ID,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { BRAND_CONFIG, brandConfig } from './core/config/brand.config';
import { routes } from './app.routes';

registerLocaleData(localeEs);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(),
    provideRouter(routes),
    provideAppInitializer(() => firstValueFrom(inject(AuthService).restoreSession())),
    { provide: BRAND_CONFIG, useValue: brandConfig },
    { provide: LOCALE_ID, useValue: 'es' },
  ]
};
