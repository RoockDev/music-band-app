import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BrandService } from '../../../core/config/brand.service';

@Component({
  selector: 'app-home-page',
  imports: [RouterLink],
  templateUrl: './home-page.html',
  styleUrl: './home-page.scss',
})
export class HomePage {
  protected readonly brand = inject(BrandService).config;
}
