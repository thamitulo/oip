import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { TokenInfo } from '../../core/models';

type Mode = 'forgot' | 'set' | 'verify';

/**
 * Pages reached from account e-mails: request a reset link, set a password (reset or invitation) and confirm an
 * e-mail address. The token stays in the page; it is sent only to the API in a request body.
 */
@Component({
  selector: 'app-account-link',
  imports: [ReactiveFormsModule, RouterLink, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatProgressBarModule],
  template: `
    <div class="auth-wrap">
      <div class="card auth-card stack">
        <div class="brand-mark"><span class="logo"><mat-icon>radar</mat-icon></span> Opportunity Intelligence</div>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }

        @switch (mode) {
          @case ('forgot') {
            <h1 class="title">Reset your password</h1>
            @if (sent()) {
              <div class="info-banner">If an account exists for that address, we've sent a link to reset the password. It is valid
                for one hour. Check your spam folder if it doesn't arrive.</div>
              <a mat-flat-button routerLink="/login">Back to sign in</a>
            } @else {
              <p class="muted" style="margin:0">Enter the e-mail address you sign in with and we'll send you a reset link.</p>
              <form class="stack" [formGroup]="emailForm" (ngSubmit)="requestReset()">
                <mat-form-field appearance="outline">
                  <mat-label>Email</mat-label>
                  <input matInput type="email" formControlName="email" autocomplete="username" />
                </mat-form-field>
                @if (error()) { <div class="error-banner" role="alert">{{ error() }}</div> }
                <button mat-flat-button type="submit" [disabled]="loading()">Send reset link</button>
              </form>
              <p class="muted small" style="margin:0"><a routerLink="/login">Back to sign in</a></p>
            }
          }

          @case ('set') {
            @if (done()) {
              <h1 class="title">Password set</h1>
              <div class="info-banner">Your password has been saved{{ info()?.purpose === 'PASSWORD_RESET' ? ' and other sessions were signed out' : '' }}.
                You can sign in now.</div>
              <a mat-flat-button routerLink="/login">Sign in</a>
            } @else if (info(); as i) {
              <h1 class="title">{{ i.purpose === 'INVITATION' ? 'Welcome, ' + i.fullName : 'Choose a new password' }}</h1>
              @if (i.purpose === 'INVITATION') {
                <p class="muted" style="margin:0">You've been invited to <strong>{{ i.tenantName }}</strong>. Choose a password to finish
                  setting up your account.</p>
              }
              <form class="stack" [formGroup]="passwordForm" (ngSubmit)="savePassword()">
                <mat-form-field appearance="outline">
                  <mat-label>New password</mat-label>
                  <input matInput type="password" formControlName="password" autocomplete="new-password" />
                  <mat-hint>At least 10 characters</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Repeat password</mat-label>
                  <input matInput type="password" formControlName="repeat" autocomplete="new-password" />
                </mat-form-field>
                @if (error()) { <div class="error-banner" role="alert">{{ error() }}</div> }
                <button mat-flat-button type="submit" [disabled]="loading()">Save password</button>
              </form>
            } @else if (error()) {
              <h1 class="title">Link not valid</h1>
              <div class="error-banner" role="alert">{{ error() }}</div>
              <a mat-stroked-button routerLink="/forgot-password">Request a new reset link</a>
            }
          }

          @case ('verify') {
            <h1 class="title">Confirm your e-mail</h1>
            @if (done()) {
              <div class="info-banner">Thanks, your e-mail address is confirmed.</div>
              <a mat-flat-button routerLink="/dashboard">Continue</a>
            } @else if (error()) {
              <div class="error-banner" role="alert">{{ error() }}</div>
              <p class="muted small" style="margin:0">Sign in and use "Resend" on the banner to get a new link.</p>
              <a mat-stroked-button routerLink="/login">Sign in</a>
            }
          }
        }
      </div>
    </div>
  `,
  styles: `.title { margin: 0; font-size: 20px; font-weight: 600; }`,
})
export class AccountLinkComponent {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  protected readonly mode = (this.route.snapshot.data['mode'] ?? 'forgot') as Mode;
  private readonly token = this.route.snapshot.queryParamMap.get('token') ?? '';

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly sent = signal(false);
  protected readonly done = signal(false);
  protected readonly info = signal<TokenInfo | null>(null);

  protected readonly emailForm = this.fb.nonNullable.group({ email: ['', [Validators.required, Validators.email]] });
  protected readonly passwordForm = this.fb.nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(128)]],
    repeat: ['', Validators.required],
  });

  constructor() {
    if (this.mode === 'set') {
      this.loading.set(true);
      this.api.tokenInfo(this.token).subscribe({
        next: (i) => { this.info.set(i); this.loading.set(false); },
        error: (e) => { this.error.set(errorMessage(e)); this.loading.set(false); },
      });
    } else if (this.mode === 'verify') {
      this.loading.set(true);
      this.api.verifyEmail(this.token).subscribe({
        next: () => { this.done.set(true); this.loading.set(false); },
        error: (e) => { this.error.set(errorMessage(e)); this.loading.set(false); },
      });
    }
  }

  protected requestReset(): void {
    if (this.emailForm.invalid) { this.emailForm.markAllAsTouched(); return; }
    this.loading.set(true);
    this.error.set(null);
    this.api.forgotPassword(this.emailForm.getRawValue().email).subscribe({
      next: () => { this.sent.set(true); this.loading.set(false); },
      error: (e) => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
  }

  protected savePassword(): void {
    const { password, repeat } = this.passwordForm.getRawValue();
    if (this.passwordForm.invalid) {
      this.error.set('The password must be between 10 and 128 characters.');
      return;
    }
    if (password !== repeat) {
      this.error.set('The two passwords do not match.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.api.setPassword(this.token, password).subscribe({
      next: () => { this.done.set(true); this.loading.set(false); },
      error: (e) => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
  }
}
