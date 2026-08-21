import { InjectionToken } from '@angular/core';

export interface BrandConfig {
  name: string;
  shortName: string;
  tagline: string;
  logoUrl: string;
  colors: {
    accent: string;
    accentStrong: string;
    paper: string;
    ink: string;
  };
  contact: {
    email: string;
    phone: string;
    address: string;
  };
  social: ReadonlyArray<{ label: string; url: string }>;
  images: {
    hero: string;
    texture: string;
  };
}

export const brandConfig: BrandConfig = {
  name: 'Archivo Sonoro',
  shortName: 'AS',
  tagline: 'Música compartida, memoria en movimiento',
  logoUrl: '/brand/mark.svg',
  colors: {
    accent: '#b64b2a',
    accentStrong: '#7e2f1a',
    paper: '#f3eddf',
    ink: '#1d211f',
  },
  contact: {
    email: 'hola@ejemplo.org',
    phone: '+34 000 000 000',
    address: 'Plaza de la Música, 1',
  },
  social: [
    { label: 'Instagram', url: 'https://www.instagram.com/' },
    { label: 'YouTube', url: 'https://www.youtube.com/' },
  ],
  images: {
    hero: '/brand/hero-notation.svg',
    texture: '/brand/paper-texture.svg',
  },
};

export const BRAND_CONFIG = new InjectionToken<BrandConfig>('BRAND_CONFIG');
