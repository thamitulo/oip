import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ApiService, errorMessage } from '../../core/api.service';
import { LabelPipe } from '../../core/format';
import { AiSettings } from '../../core/models';
import { UiStateService } from '../../core/ui-state.service';

@Component({
  selector: 'app-ai-settings',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, LabelPipe],
  template: `
    <div class="page">
      <div class="page-header"><div><h1>AI settings</h1>
        <p>Configure the assistant and AI features. AI output is always labelled and stored with model and prompt version; it never overrides source facts.</p></div></div>
      @if (error()) { <div class="error-banner" style="margin-bottom:12px">{{ error() }}</div> }
      @if (!configured()) {
        <div class="warn-banner" style="margin-bottom:12px">No AI provider key is configured. Set <code>ANTHROPIC_API_KEY</code> in the
          server environment (see .env.example) and restart the API. Settings below take effect once a key is present.</div>
      } @else { <div class="ai-banner" style="margin-bottom:12px">Provider: Anthropic (Claude) · key configured on the server.</div> }
      @if (settings(); as s) {
        <div class="grid grid-2">
          <section class="card stack-sm">
            <h2>Model</h2>
            <mat-checkbox [(ngModel)]="s.enabled">AI features enabled</mat-checkbox>
            <mat-form-field appearance="outline"><mat-label>Model</mat-label>
              <mat-select [(ngModel)]="s.model">
                <mat-option value="claude-opus-5-5">Claude Opus 5.5 (default)</mat-option>
                <mat-option value="claude-sonnet-5-5">Claude Sonnet 5.5 (faster, cheaper)</mat-option>
                <mat-option value="claude-haiku-5-5">Claude Haiku 5.5 (fastest, cheapest)</mat-option>
                <mat-option value="claude-fable-5-2">Claude Fable 5.2 (most capable)</mat-option>
              </mat-select></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Reasoning effort</mat-label>
              <mat-select [(ngModel)]="s.effort">@for (e of efforts; track e) { <mat-option [value]="e">{{ e | label }}</mat-option> }</mat-select>
              <mat-hint>Higher effort is more thorough but slower and costs more.</mat-hint></mat-form-field>
            <mat-checkbox [(ngModel)]="s.serverSideFallback">Use the provider's refusal fallback model when supported</mat-checkbox>
          </section>
          <section class="card stack-sm">
            <h2>Features and limits</h2>
            <mat-checkbox [(ngModel)]="s.assistantEnabled">Assistant</mat-checkbox>
            <mat-form-field appearance="outline"><mat-label>Assistant requests per business per day</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="s.assistantDailyLimitPerTenant" /></mat-form-field>
            <mat-checkbox [(ngModel)]="s.matchExplanationEnabled">AI match assessment ("Assess fit with AI")</mat-checkbox>
            <mat-checkbox [(ngModel)]="s.classificationEnabled">AI classification of opportunities</mat-checkbox>
            <mat-form-field appearance="outline"><mat-label>Classifications per day (platform)</mat-label>
              <input matInput type="number" min="0" [(ngModel)]="s.classificationDailyLimit" /></mat-form-field>
          </section>
        </div>
        <div class="row" style="margin-top:12px"><button mat-flat-button (click)="save(s)">Save AI settings</button></div>
      }
      <section class="card" style="margin-top:16px">
        <h2>Usage (last 30 days)</h2>
        <div class="table-scroll"><table class="data">
          <thead><tr><th>Day</th><th>Feature</th><th>Status</th><th class="num">Calls</th><th class="num">Input tokens</th><th class="num">Output tokens</th></tr></thead>
          <tbody>@for (u of usage(); track $index) {
            <tr><td>{{ u.day }}</td><td>{{ u.feature | label }}</td><td>{{ u.status | label }}</td><td class="num">{{ u.calls }}</td>
              <td class="num">{{ u.input_tokens }}</td><td class="num">{{ u.output_tokens }}</td></tr>
          } @empty { <tr><td colspan="6" class="empty">No AI usage yet.</td></tr> }</tbody>
        </table></div>
      </section>
    </div>
  `,
})
export class AiSettingsComponent {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);
  private readonly ui = inject(UiStateService);
  protected readonly efforts = ['low', 'medium', 'high', 'xhigh', 'max'];
  protected readonly settings = signal<AiSettings | null>(null);
  protected readonly configured = signal(false);
  protected readonly usage = signal<{ day: string; feature: string; status: string; calls: number; input_tokens: number; output_tokens: number }[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.api.aiAdminSettings().subscribe({
      next: (r) => { this.settings.set(r.settings); this.configured.set(r.providerConfigured); },
      error: (e) => this.error.set(errorMessage(e)),
    });
    this.api.aiUsage().subscribe({ next: (u) => this.usage.set(u), error: () => undefined });
  }

  protected save(s: AiSettings): void {
    this.api.saveAiSettings(s).subscribe({
      next: (x) => { this.settings.set(x); this.ui.loadAiStatus(); this.snack.open('AI settings saved', 'OK', { duration: 2500 }); },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }
}
