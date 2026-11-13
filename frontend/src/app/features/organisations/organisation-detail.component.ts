import { Component, inject, input, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { forkJoin, switchMap } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api.service';
import { DateTimePipe, LabelPipe } from '../../core/format';
import { OpportunitySummary, OrganisationDetail } from '../../core/models';
import { OpportunityTableComponent } from '../../shared/opportunity-table.component';
import { AuthService } from '../../core/auth.service';
import { ChannelEditorComponent, OrganisationFormComponent } from './organisation-editor.component';

@Component({
  selector: 'app-organisation-detail',
  imports: [RouterLink, MatButtonModule, MatIconModule, LabelPipe, DateTimePipe, OpportunityTableComponent, OrganisationFormComponent,
    ChannelEditorComponent],
  template: `
    <div class="page">
      <a mat-button routerLink="/organisations"><mat-icon>arrow_back</mat-icon>Organisations</a>
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      @if (data(); as d) {
        <div class="page-header" style="margin-top:8px">
          <div>
            <h1>{{ d.organisation.name }}</h1>
            <p>{{ d.organisation.organisationType | label }} · {{ d.organisation.sector | label }}
              @if (d.primaryCommodities) { · {{ d.primaryCommodities }} }</p>
          </div>
          <div class="row">
            @if (d.organisation.website) {
              <a mat-stroked-button [href]="d.organisation.website" target="_blank" rel="noopener noreferrer"><mat-icon>open_in_new</mat-icon>Website</a>
            }
            @if (isAdmin() && !editing()) { <button mat-stroked-button (click)="editing.set(true)"><mat-icon>edit</mat-icon>Edit</button> }
          </div>
        </div>
        @if (editing()) {
          <app-organisation-form [organisation]="d" (saved)="data.set($event); editing.set(false)" (cancelled)="editing.set(false)" />
          <div style="height:12px"></div>
        }
        @if (d.notes) { <div class="info-banner" style="margin-bottom:16px">{{ d.notes }}</div> }
        <div class="grid grid-2">
          <section class="card">
            <div class="row" style="justify-content:space-between;margin-bottom:4px">
              <h2 style="margin:0">How this organisation procures</h2>
              @if (isAdmin() && channelEdit() === null) { <button mat-button (click)="channelEdit.set('new')"><mat-icon>add</mat-icon>Add channel</button> }
            </div>
            @if (channelEdit() === 'new') {
              <app-channel-editor [organisationId]="d.organisation.id" (saved)="data.set($event); channelEdit.set(null)" (cancelled)="channelEdit.set(null)" />
            }
            @for (c of d.channels; track c.id) {
              <div style="padding:10px 0;border-bottom:1px solid var(--oip-border)">
                <div class="row">
                  <strong>{{ c.name }}</strong>
                  <span class="pill" [class.ok]="c.verificationStatus === 'VERIFIED'" [class.warn]="c.verificationStatus !== 'VERIFIED'">{{ c.verificationStatus | label }}</span>
                  @if (isAdmin() && channelEdit() === null) {
                    <span class="spacer"></span>
                    <button mat-icon-button aria-label="Edit channel" (click)="channelEdit.set(c.id)"><mat-icon>edit</mat-icon></button>
                    <button mat-icon-button aria-label="Delete channel" (click)="removeChannel(d.organisation.id, c.id, c.name)"><mat-icon>delete</mat-icon></button>
                  }
                </div>
                @if (channelEdit() === c.id) {
                  <app-channel-editor [organisationId]="d.organisation.id" [channel]="c" (saved)="data.set($event); channelEdit.set(null)" (cancelled)="channelEdit.set(null)" />
                }
                <div class="muted small">{{ c.channelType | label }}@if (c.supplierPlatform) { · {{ c.supplierPlatform }} }</div>
                @if (c.description) { <div class="small" style="margin-top:4px">{{ c.description }}</div> }
                @if (c.url) { <a class="small" [href]="c.url" target="_blank" rel="noopener noreferrer">{{ c.url }}</a> }
              </div>
            } @empty { <div class="muted">No procurement channels recorded yet.</div> }
          </section>
          <section class="card">
            <h2>Monitored sources</h2>
            @for (s of d.sources; track s.id) {
              <div style="padding:10px 0;border-bottom:1px solid var(--oip-border)">
                <div class="row"><strong>{{ s.name }}</strong><span class="pill">{{ s.integrationStatus | label }}</span>
                  @if (s.enabled) { <span class="pill ok">Crawled</span> } @else { <span class="pill warn">Not crawled</span> }</div>
                <div class="muted small">Last successful crawl: {{ s.lastSuccessfulCrawlAt ? (s.lastSuccessfulCrawlAt | dateTime) : 'never' }}</div>
                @if (s.verificationNotes) { <div class="small" style="margin-top:4px">{{ s.verificationNotes }}</div> }
              </div>
            } @empty { <div class="muted">This organisation's opportunities are discovered through a shared source such as eTenders.</div> }
          </section>
        </div>
        <h2 style="font:var(--mat-sys-title-medium);margin:24px 0 12px">Active opportunities ({{ total() }})</h2>
        <app-opportunity-table [items]="opportunities()" emptyText="No active opportunities from this organisation." />
      }
    </div>
  `,
})
export class OrganisationDetailComponent {
  private readonly api = inject(ApiService);
  readonly id = input.required<string>();
  protected readonly data = signal<OrganisationDetail | null>(null);
  protected readonly opportunities = signal<OpportunitySummary[]>([]);
  protected readonly total = signal(0);
  protected readonly error = signal<string | null>(null);
  protected readonly isAdmin = inject(AuthService).isPlatformAdmin;
  protected readonly editing = signal(false);
  /** null: not editing; 'new': adding; otherwise the id of the channel being edited. */
  protected readonly channelEdit = signal<string | null>(null);

  protected removeChannel(orgId: string, channelId: string, name: string): void {
    if (!confirm(`Remove the channel "${name}"?`)) return;
    this.api.deleteChannel(orgId, channelId).subscribe({
      next: () => this.api.organisation(orgId).subscribe((d) => this.data.set(d)),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  constructor() {
    toObservable(this.id).pipe(
      switchMap((id) => forkJoin({ org: this.api.organisation(id), opps: this.api.opportunities({ organisationId: id, size: 20 }) })),
    ).subscribe({
      next: ({ org, opps }) => { this.data.set(org); this.opportunities.set(opps.content); this.total.set(opps.totalElements); },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }
}
