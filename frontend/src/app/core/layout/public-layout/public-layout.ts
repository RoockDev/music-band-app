import { Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { BrandService } from '../../config/brand.service';

@Component({
  selector: 'app-public-layout',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './public-layout.html',
  styleUrl: './public-layout.scss',
})
export class PublicLayout {
  protected readonly brand = inject(BrandService).config;
  protected readonly menuOpen = signal(false);

  protected closeMenu(): void {
    this.menuOpen.set(false);
  }
}
