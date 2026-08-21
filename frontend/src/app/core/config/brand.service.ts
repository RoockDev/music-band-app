import { DOCUMENT } from '@angular/common';
import { inject, Injectable } from '@angular/core';
import { BRAND_CONFIG } from './brand.config';

@Injectable({ providedIn: 'root' })
export class BrandService {
  private readonly document = inject(DOCUMENT);
  readonly config = inject(BRAND_CONFIG);

  applyTheme(): void {
    const root = this.document.documentElement;
    root.style.setProperty('--color-accent', this.config.colors.accent);
    root.style.setProperty('--color-accent-strong', this.config.colors.accentStrong);
    root.style.setProperty('--color-paper', this.config.colors.paper);
    root.style.setProperty('--color-ink', this.config.colors.ink);
    this.document.title = this.config.name;
  }
}
