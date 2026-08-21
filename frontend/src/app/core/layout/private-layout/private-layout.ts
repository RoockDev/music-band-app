import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { BrandService } from '../../config/brand.service';

@Component({
  selector: 'app-private-layout',
  imports: [RouterLink, RouterOutlet],
  templateUrl: './private-layout.html',
  styleUrl: './private-layout.scss',
})
export class PrivateLayout {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly brand = inject(BrandService).config;
  protected readonly session = this.auth.session;
  protected readonly signingOut = signal(false);
  protected readonly signOutFailed = signal(false);

  protected logout(): void {
    this.signingOut.set(true);
    this.signOutFailed.set(false);
    this.auth
      .logout()
      .pipe(finalize(() => this.signingOut.set(false)))
      .subscribe({
        next: () => void this.router.navigateByUrl('/'),
        error: () => this.signOutFailed.set(true),
      });
  }
}
