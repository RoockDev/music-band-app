import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { BrandService } from '../../../core/config/brand.service';
import { PublicContentService } from '../data/public-content.service';

@Component({
  selector: 'app-contact-page',
  imports: [ReactiveFormsModule],
  templateUrl: './contact-page.html',
  styleUrl: './contact-page.scss',
})
export class ContactPage {
  private readonly formBuilder = inject(FormBuilder);
  private readonly content = inject(PublicContentService);
  protected readonly brand = inject(BrandService).config;
  protected readonly sending = signal(false);
  protected readonly sent = signal(false);
  protected readonly failed = signal(false);

  protected readonly form = this.formBuilder.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    message: ['', [Validators.required, Validators.maxLength(5000)]],
  });

  protected submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.sending()) {
      return;
    }

    this.sending.set(true);
    this.sent.set(false);
    this.failed.set(false);
    this.content
      .submitContact(this.form.getRawValue())
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: () => {
          this.sent.set(true);
          this.form.reset();
        },
        error: () => this.failed.set(true),
      });
  }
}
