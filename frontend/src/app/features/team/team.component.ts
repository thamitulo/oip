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
import { AddMemberResult, Member, TENANT_ROLES } from '../../core/models';

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
                <td class="strong">{{ m.fullName }}</td><td>{{ m.email }}</td>
                <td>
                  @if (canManage && m.id !== auth.user()?.userId) {
                    <mat-select [value]="m.role" (selectionChange)="update(m, { role: $event.value })" aria-label="Role" style="width:200px">
                      @for (r of roles; track r) { <mat-option [value]="r">{{ r | label }}</mat-option> }
                    </mat-select>
                  } @else { {{ m.role | label }} }
                </td>
                <td>
                  @if (m.invitationPending) {
                    <span class="pill warn">Invitation pending</span>
                    @if (canManage) { <button mat-button (click)="resend(m)">Resend</button> }
                  } @else {
                    <span class="pill" [class.ok]="m.status === 'ACTIVE'">{{ m.status | label }}</span>
                  }
                  @if (canManage && m.id !== auth.user()?.userId && !m.invitationPending) {
                    <button mat-button (click)="update(m, { status: m.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' })">
                      {{ m.status === 'ACTIVE' ? 'Disable' : 'Enable' }}</button>
                  }
                </td>
                <td class="muted">{{ m.lastLoginAt ? (m.lastLoginAt | dateTime) : 'Never' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>

      @if (canManage) {
        <form class="card" [formGroup]="form" (ngSubmit)="add()">
          <h2>Invite a team member</h2>
          <p class="muted small" style="margin:-6px 0 12px">They'll get an e-mail with a link to set their own password.
            Someone who already has an account is added straight away.</p>
          @if (invite(); as r) {
            @if (r.invitationUrl) {
              <div class="warn-banner" style="margin-bottom:12px">
                E-mail delivery is off, so no invitation was sent. Share this one-time link with {{ r.member.fullName }} privately
                (valid for 7 days):
                <div class="row" style="margin-top:6px"><code class="mono link">{{ r.invitationUrl }}</code>
                  <button mat-stroked-button type="button" (click)="copy(r.invitationUrl)">{{ copied() ? 'Copied' : 'Copy' }}</button></div>
              </div>
            } @else if (r.invitationEmailed) {
              <div class="info-banner" style="margin-bottom:12px">Invitation e-mailed to {{ r.member.email }}.</div>
            } @else {
              <div class="info-banner" style="margin-bottom:12px">{{ r.member.fullName }} already had an account and has been added.</div>
            }
          }
          <div class="invite-row">
            <mat-form-field appearance="outline"><mat-label>Full name</mat-label><input matInput formControlName="fullName" /></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Email</mat-label><input matInput type="email" formControlName="email" /></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Role</mat-label>
              <mat-select formControlName="role">@for (r of roles; track r) { <mat-option [value]="r">{{ r | label }}</mat-option> }</mat-select>
            </mat-form-field>
            <button mat-flat-button type="submit" [disabled]="form.invalid || busy()"><mat-icon>send</mat-icon>Invite</button>
          </div>
        </form>
      }
    </div>
  `,
  styles: `
    .invite-row { display: grid; grid-template-columns: 1fr 1.3fr 220px auto; gap: 10px; align-items: start; }
    .invite-row button { height: 48px; }
    .link { word-break: break-all; flex: 1; background: #fff; padding: 4px 6px; border-radius: 6px; }
    @media (max-width: 900px) { .invite-row { grid-template-columns: 1fr; } }
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
  });
  protected readonly invite = signal<AddMemberResult | null>(null);
  protected readonly busy = signal(false);
  protected readonly copied = signal(false);

  constructor() {
    this.reload();
  }

  protected add(): void {
    this.busy.set(true);
    this.error.set(null);
    this.copied.set(false);
    this.api.addMember(this.form.getRawValue()).subscribe({
      next: (r) => { this.invite.set(r); this.form.reset(); this.busy.set(false); this.reload(); },
      error: (e) => { this.error.set(errorMessage(e)); this.busy.set(false); },
    });
  }

  protected resend(m: Member): void {
    this.error.set(null);
    this.copied.set(false);
    this.api.resendInvitation(m.id).subscribe({ next: (r) => this.invite.set(r), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected copy(text: string): void {
    void navigator.clipboard?.writeText(text).then(() => this.copied.set(true));
  }

  protected update(m: Member, change: { role?: string; status?: string }): void {
    this.error.set(null);
    this.api.updateMember(m.id, change).subscribe({ next: () => this.reload(), error: (e) => { this.error.set(errorMessage(e)); this.reload(); } });
  }

  private reload(): void {
    this.api.members().subscribe({ next: (m) => this.members.set(m), error: (e) => this.error.set(errorMessage(e)) });
  }
}
