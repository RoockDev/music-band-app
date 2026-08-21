import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-private-home-page',
  imports: [RouterLink],
  templateUrl: './private-home-page.html',
  styleUrl: './private-home-page.scss',
})
export class PrivateHomePage {
  protected readonly session = inject(AuthService).session;
}
