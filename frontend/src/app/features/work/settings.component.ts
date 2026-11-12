import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { MatchSettings, NotificationPreferences, PROVINCES } from '../../core/models';

@Component({
  selector: 'app-settings',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule],
  template: `
    <div class="page">
      <div class="page-header"><div><h1>Settings</h1><p>Notifications, matching and your personal data.</p></div></div>
      @if (error()) { <div class="error-banner" style="margin-bottom:12px">{{ error() }}</div> }
      <div class="grid grid-2">
        @if (prefs(); as p) {
          <section class="card">
            <h2>Notifications</h2>
            <div class="stack-sm">
              <mat-checkbox [(ngModel)]="p.inAppEnabled">In-app notifications</mat-checkbox>
              <mat-checkbox [(ngModel)]="p.emailEnabled">E-mail notifications</mat-checkbox>
              <mat-form-field appearance="outline"><mat-label>E-mail frequency</mat-label>
                <mat-select [(ngModel)]="p.emailFrequency">
                  <mat-option value="IMMEDIATE">Immediately</mat-option><mat-option value="DAILY_DIGEST">Daily digest (07:00)</mat-option>
                  <mat-option value="OFF">Off</mat-option></mat-select></mat-form-field>
              <mat-checkbox [(ngModel)]="p.urgentAlerts">Send urgent alerts immediately (closing soon, date changes, assignments)</mat-checkbox>
              <div class="row">
                <mat-form-field appearance="outline"><mat-label>Notify me of matches from</mat-label>
                  <input matInput type="number" min="0" max="100" [(ngModel)]="p.minMatchScore" /><span matTextSuffix>%</span></mat-form-field>
                <mat-form-field appearance="outline"><mat-label>Closing reminder</mat-label>
                  <input matInput type="number" min="0" max="30" [(ngModel)]="p.closingReminderDays" /><span matTextSuffix>days</span></mat-form-field>
              </div>
              <mat-form-field appearance="outline"><mat-label>Only these provinces (optional)</mat-label>
                <mat-select multiple [ngModel]="csv(p.provinces)" (ngModelChange)="p.provinces = $event.join(',')">
                  @for (pr of provinces; track pr.code) { <mat-option [value]="pr.code">{{ pr.label }}</mat-option> }</mat-select></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Only these sectors (optional)</mat-label>
                <mat-select multiple [ngModel]="csv(p.sectors)" (ngModelChange)="p.sectors = $event.join(',')">
                  <mat-option value="GOVERNMENT">Government</mat-option><mat-option value="MINING">Mining</mat-option></mat-select></mat-form-field>
              <div><button mat-flat-button (click)="savePrefs(p)">Save notification settings</button></div>
            </div>
          </section>
        }

        @if (weights(); as w) {
          <section class="card" id="matching">
            <h2>Matching weights</h2>
            <p class="small muted" style="margin-top:0">How much each dimension contributes to your match score. Must add up to 100.
              Missing mandatory requirements are always shown separately, whatever the score.</p>
            <div class="weights">
              @for (f of weightFields; track f.key) {
                <mat-form-field appearance="outline"><mat-label>{{ f.label }}</mat-label>
                  <input matInput type="number" min="0" max="100" [(ngModel)]="$any(w)[f.key]" [disabled]="!canManage" /></mat-form-field>
              }
            </div>
            <div class="small" [class.bad]="sum(w) !== 100">Total: {{ sum(w) }} / 100</div>
            <div class="row" style="margin-top:8px">
              <mat-form-field appearance="outline"><mat-label>Strong match from</mat-label><input matInput type="number" [(ngModel)]="w.strongThreshold" [disabled]="!canManage" /></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Possible match from</mat-label><input matInput type="number" [(ngModel)]="w.possibleThreshold" [disabled]="!canManage" /></mat-form-field>
            </div>
            @if (canManage) { <button mat-flat-button (click)="saveWeights(w)" [disabled]="sum(w) !== 100">Save matching weights</button> }
            @else { <div class="xs muted">Only owners and admins can change matching weights.</div> }
          </section>
        }

        <section class="card">
          <h2>Your personal information (POPIA)</h2>
          <p class="small muted" style="margin-top:0">Download everything the platform holds about you, or delete your account. Deleting
            anonymises your user record and removes your sessions, notifications, preferences and assistant conversations.</p>
          <button mat-stroked-button (click)="exportData()">Download my data (JSON)</button>
          <div class="divider"></div>
          <div class="row">
            <mat-form-field appearance="outline"><mat-label>Confirm with your password</mat-label>
              <input matInput type="password" [(ngModel)]="password" autocomplete="current-password" /></mat-form-field>
            <button mat-stroked-button class="danger" (click)="deleteAccount()" [disabled]="!password">Delete my account</button>
          </div>
        </section>
      </div>
    </div>
  `,
  styles: `
    .weights { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 0 10px; }
    .bad { color: var(--oip-danger); font-weight: 600; }
    .danger { color: var(--oip-danger) !important; }
  `,
})
export class SettingsComponent {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly snack = inject(MatSnackBar);
  protected readonly canManage = this.auth.hasPermission('TENANT_MANAGE');
  protected readonly provinces = PROVINCES;
  protected readonly prefs = signal<NotificationPreferences | null>(null);
  protected readonly weights = signal<MatchSettings | null>(null);
  protected readonly error = signal<string | null>(null);
  protected password = '';
  protected readonly weightFields = [
    { key: 'weightServices', label: 'Services & products' }, { key: 'weightIndustry', label: 'Industry' },
    { key: 'weightCapability', label: 'Capabilities' }, { key: 'weightGeography', label: 'Geography' },
    { key: 'weightRegistrations', label: 'Registrations' }, { key: 'weightOther', label: 'Other eligibility' },
  ];
  protected readonly loaded = computed(() => this.prefs() !== null);

  constructor() {
    this.api.notificationPreferences().subscribe({ next: (p) => this.prefs.set(p), error: (e) => this.error.set(errorMessage(e)) });
    this.api.matchSettings().subscribe({ next: (w) => this.weights.set(w), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected csv(v: string | null): string[] {
    return v ? v.split(',').filter(Boolean) : [];
  }

  protected sum(w: MatchSettings): number {
    return [w.weightServices, w.weightIndustry, w.weightCapability, w.weightGeography, w.weightRegistrations, w.weightOther]
      .reduce((a, b) => a + Number(b || 0), 0);
  }

  protected savePrefs(p: NotificationPreferences): void {
    this.api.saveNotificationPreferences(p).subscribe({
      next: (x) => { this.prefs.set(x); this.snack.open('Notification settings saved', 'OK', { duration: 2500 }); },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected saveWeights(w: MatchSettings): void {
    this.api.saveMatchSettings(w).subscribe({
      next: (x) => { this.weights.set(x); this.snack.open('Matching weights saved; matches are being recalculated', 'OK', { duration: 3000 }); },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected exportData(): void {
    this.api.exportMyData().subscribe((data) => {
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'my-oip-data.json';
      a.click();
      URL.revokeObjectURL(url);
    });
  }

  protected deleteAccount(): void {
    if (!confirm('Delete your account permanently? This cannot be undone.')) return;
    this.api.deleteMyAccount(this.password).subscribe({
      next: () => { this.auth.clear(); location.href = '/login'; },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }
}
