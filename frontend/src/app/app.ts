import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { BrandService } from './core/config/brand.service';

@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  private readonly brand = inject(BrandService);

  constructor() {
    this.brand.applyTheme();
  }
}
