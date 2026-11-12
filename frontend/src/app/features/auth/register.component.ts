import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink, MatFormFieldModule, MatInputModule, MatButtonModule, MatCheckboxModule, MatIconModule],
  template: `
    <div class="auth-wrap">
      <form class="card auth-card stack" [formGroup]="form" (ngSubmit)="submit()">
        <div class="brand-mark"><mat-icon>radar</mat-icon> Opportunity Intelligence</div>
        <h1 style="margin:0;font:var(--mat-sys-headline-small)">Create a business account</h1>
        @if (error()) { <div class="error-banner" role="alert">{{ error() }}</div> }
        <mat-form-field appearance="outline">
          <mat-label>Company name</mat-label>
          <input matInput formControlName="companyName" autocomplete="organization" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Your full name</mat-label>
          <input matInput formControlName="fullName" autocomplete="name" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Work email</mat-label>
          <input matInput type="email" formControlName="email" autocomplete="email" />
          @if (form.controls.email.hasError('email')) { <mat-error>Enter a valid email address</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Password</mat-label>
          <input matInput type="password" formControlName="password" autocomplete="new-password" />
          <mat-hint>At least 10 characters</mat-hint>
          @if (form.controls.password.hasError('minlength')) { <mat-error>Use at least 10 characters</mat-error> }
        </mat-form-field>
        <mat-checkbox formControlName="acceptTerms">
          I accept the terms of use and the processing of my personal information as described in the privacy notice (POPIA).
        </mat-checkbox>
        <mat-checkbox formControlName="marketingConsent">Send me product news by email (optional)</mat-checkbox>
        <button mat-flat-button type="submit" [disabled]="busy() || !form.controls.acceptTerms.value">
          {{ busy() ? 'Creating account…' : 'Create account' }}
        </button>
        <p class="muted small" style="margin:0">Already registered? <a routerLink="/login">Sign in</a></p>
      </form>
    </div>
  `,
})
export class RegisterComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    companyName: ['', [Validators.required, Validators.maxLength(200)]],
    fullName: ['', [Validators.required, Validators.maxLength(200)]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(128)]],
    acceptTerms: [false, Validators.requiredTrue],
    marketingConsent: [false],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.auth.register(this.form.getRawValue()).subscribe({
      next: () => void this.router.navigate(['/profile'], { queryParams: { welcome: 1 } }),
      error: (e) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
