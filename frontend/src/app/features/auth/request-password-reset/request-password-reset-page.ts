import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-request-password-reset-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './request-password-reset-page.html',
  styleUrl: '../auth-page.scss',
})
export class RequestPasswordResetPage {
  private readonly formBuilder = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);

  protected readonly form = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
  });

  protected submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting() || this.submitted()) {
      return;
    }

    this.submitting.set(true);
    const request = { email: this.form.controls.email.value.trim() };
    this.auth
      .requestPasswordReset(request)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => this.submitted.set(true),
        // Keep transport/SMTP/account state out of the UI response. Retrying later remains safe.
        error: () => this.submitted.set(true),
      });
  }
}
