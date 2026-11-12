import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DateTimePipe, LabelPipe } from '../../core/format';
import { Member, TENANT_ROLES } from '../../core/models';

@Component({
  selector: 'app-team',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, LabelPipe, DateTimePipe],
  template: `
    <div class="page">
      <div class="page-header"><div><h1>Team</h1><p>People in {{ auth.user()?.tenantName }} and their roles</p></div></div>
      @if (error()) { <div class="error-banner" style="margin-bottom:12px">{{ error() }}</div> }
      <div class="card table-scroll" style="padding:0;margin-bottom:16px">
        <table class="data">
          <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Last sign-in</th></tr></thead>
          <tbody>
            @for (m of members(); track m.id) {
              <tr>
                <td>{{ m.fullName }}</td><td>{{ m.email }}</td>
                <td>
                  @if (canManage && m.id !== auth.user()?.userId) {
                    <mat-select [value]="m.role" (selectionChange)="update(m, { role: $event.value })" aria-label="Role" style="width:200px">
                      @for (r of roles; track r) { <mat-option [value]="r">{{ r | label }}</mat-option> }
                    </mat-select>
                  } @else { {{ m.role | label }} }
                </td>
                <td>
                  <span class="pill" [class.ok]="m.status === 'ACTIVE'">{{ m.status | label }}</span>
                  @if (canManage && m.id !== auth.user()?.userId) {
                    <button mat-button (click)="update(m, { status: m.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' })">
                      {{ m.status === 'ACTIVE' ? 'Disable' : 'Enable' }}</button>
                  }
                </td>
                <td>{{ m.lastLoginAt | dateTime }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>

      @if (canManage) {
        <form class="card" [formGroup]="form" (ngSubmit)="add()">
          <h2>Add a team member</h2>
          <div class="row" style="align-items:flex-start">
            <mat-form-field appearance="outline"><mat-label>Full name</mat-label><input matInput formControlName="fullName" /></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Email</mat-label><input matInput type="email" formControlName="email" /></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Role</mat-label>
              <mat-select formControlName="role">@for (r of roles; track r) { <mat-option [value]="r">{{ r | label }}</mat-option> }</mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Initial password</mat-label><input matInput type="password" formControlName="initialPassword" autocomplete="new-password" />
              <mat-hint>Share securely; at least 10 characters</mat-hint></mat-form-field>
            <button mat-flat-button type="submit" [disabled]="form.invalid">Add</button>
          </div>
        </form>
      }
    </div>
  `,
})
export class TeamComponent {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);
  protected readonly canManage = this.auth.hasPermission('USERS_MANAGE');
  protected readonly roles = TENANT_ROLES;
  protected readonly members = signal<Member[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    fullName: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    role: ['STANDARD_USER', Validators.required],
    initialPassword: ['', [Validators.required, Validators.minLength(10)]],
  });

  constructor() {
    this.reload();
  }

  protected add(): void {
    this.api.addMember(this.form.getRawValue()).subscribe({
      next: () => { this.form.reset(); this.reload(); },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected update(m: Member, change: { role?: string; status?: string }): void {
    this.error.set(null);
    this.api.updateMember(m.id, change).subscribe({ next: () => this.reload(), error: (e) => { this.error.set(errorMessage(e)); this.reload(); } });
  }

  private reload(): void {
    this.api.members().subscribe({ next: (m) => this.members.set(m), error: (e) => this.error.set(errorMessage(e)) });
  }
}
