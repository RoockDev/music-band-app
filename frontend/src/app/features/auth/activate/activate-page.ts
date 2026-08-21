import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-activate-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './activate-page.html',
  styleUrl: '../auth-page.scss',
})
export class ActivatePage {
  private readonly formBuilder = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token');
  protected readonly submitting = signal(false);
  protected readonly completed = signal(false);
  protected readonly failed = signal(false);
  protected readonly missingToken = !this.token;

  protected readonly form = this.formBuilder.nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(64)]],
    confirmation: ['', [Validators.required, Validators.maxLength(64)]],
  });

  protected submit(): void {
    this.form.markAllAsTouched();
    if (
      this.form.invalid ||
      this.form.controls.password.value !== this.form.controls.confirmation.value ||
      !this.token ||
      this.submitting()
    ) {
      return;
    }

    this.submitting.set(true);
    this.failed.set(false);
    this.auth
      .activateAccount({ token: this.token, newPassword: this.form.controls.password.value })
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({ next: () => this.completed.set(true), error: () => this.failed.set(true) });
  }
}
