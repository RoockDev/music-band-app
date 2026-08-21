import { Component, inject } from '@angular/core';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-private-home-page',
  templateUrl: './private-home-page.html',
  styleUrl: './private-home-page.scss',
})
export class PrivateHomePage {
  protected readonly session = inject(AuthService).session;
}
