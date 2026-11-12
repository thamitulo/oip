import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api.service';
import { DateTimePipe, LabelPipe } from '../../core/format';
import { Source, SourceHealth } from '../../core/models';

@Component({
  selector: 'app-source-list',
  imports: [RouterLink, ReactiveFormsModule, MatButtonModule, MatButtonToggleModule, MatIconModule, MatTooltipModule, LabelPipe, DateTimePipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div><h1>Source management</h1><p>Every place opportunities are collected from, with access and verification status</p></div>
        <a mat-flat-button routerLink="/admin/sources/new"><mat-icon>add</mat-icon>Add source</a>
      </div>
      <div class="grid grid-stats" style="margin-bottom:16px">
        <div class="stat"><div class="value">{{ counts().enabled }}</div><div class="label">Enabled for crawling</div></div>
        <div class="stat"><div class="value">{{ counts().pending }}</div><div class="label">Pending verification</div></div>
        <div class="stat"><div class="value">{{ counts().failing }}</div><div class="label">Failing</div></div>
        <div class="stat"><div class="value">{{ sources().length }}</div><div class="label">Registered sources</div></div>
      </div>
      <mat-button-toggle-group [formControl]="filter" style="margin-bottom:12px" aria-label="Filter">
        <mat-button-toggle value="ALL">All</mat-button-toggle>
        <mat-button-toggle value="ENABLED">Enabled</mat-button-toggle>
        <mat-button-toggle value="PENDING">Pending verification</mat-button-toggle>
        <mat-button-toggle value="ATTENTION">Needs attention</mat-button-toggle>
      </mat-button-toggle-group>
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      <div class="card table-scroll" style="padding:0">
        <table class="data">
          <thead><tr><th>Source</th><th>Status</th><th>Adapter</th><th>Health</th><th>Last success</th><th class="num">Opportunities</th></tr></thead>
          <tbody>
            @for (s of visible(); track s.id) {
              @let h = health()[s.id];
              <tr class="clickable" (click)="open(s.id)" (keydown.enter)="open(s.id)" tabindex="0">
                <td><div>{{ s.name }}</div><div class="muted small">{{ s.organisationName ?? '—' }} · {{ s.sector | label }}</div></td>
                <td>
                  <span class="pill" [class]="statusClass(s)">{{ s.integrationStatus | label }}</span>
                  @if (s.enabled) { <span class="pill ok">Enabled</span> }
                </td>
                <td>{{ s.adapterType ? (s.adapterType | label) : '—' }}</td>
                <td>
                  @if (h) { <span class="pill" [class]="healthClass(h.health)" [matTooltip]="h.lastOutcome ? 'Last run: ' + h.lastOutcome : ''">{{ h.health | label }}</span> }
                </td>
                <td class="small">{{ s.lastSuccessfulCrawlAt | dateTime }}</td>
                <td class="num">{{ h?.opportunityCount ?? 0 }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  `,
  styles: `.num { text-align: right; }`,
})
export class SourceListComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly filter = new FormControl('ALL', { nonNullable: true });
  private readonly filterValue = signal('ALL');
  protected readonly sources = signal<Source[]>([]);
  protected readonly health = signal<Record<string, SourceHealth>>({});
  protected readonly error = signal<string | null>(null);

  protected readonly counts = computed(() => {
    const h = Object.values(this.health());
    return {
      enabled: this.sources().filter((s) => s.enabled).length,
      pending: this.sources().filter((s) => s.integrationStatus === 'PENDING_VERIFICATION').length,
      failing: h.filter((x) => x.health === 'FAILING').length,
    };
  });

  protected readonly visible = computed(() => {
    const f = this.filterValue();
    const h = this.health();
    return this.sources().filter((s) =>
      f === 'ALL' ||
      (f === 'ENABLED' && s.enabled) ||
      (f === 'PENDING' && s.integrationStatus === 'PENDING_VERIFICATION') ||
      (f === 'ATTENTION' && ['FAILING', 'STALE'].includes(h[s.id]?.health ?? '')));
  });

  constructor() {
    this.filter.valueChanges.subscribe((v) => this.filterValue.set(v));
    forkJoin({ sources: this.api.sources({ size: 200 }), health: this.api.sourceHealth() }).subscribe({
      next: ({ sources, health }) => {
        this.sources.set(sources.content);
        this.health.set(Object.fromEntries(health.map((h) => [h.sourceId, h])));
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected open(id: string): void {
    void this.router.navigate(['/admin/sources', id]);
  }

  protected statusClass(s: Source): string {
    if (['ACTIVE_PUBLIC', 'ACTIVE_API', 'AUTHORISED_INTEGRATION'].includes(s.integrationStatus)) return 'pill ok';
    if (s.integrationStatus === 'PENDING_VERIFICATION') return 'pill warn';
    if (s.integrationStatus === 'TEMPORARILY_UNAVAILABLE') return 'pill danger';
    return 'pill';
  }

  protected healthClass(h: string): string {
    return h === 'HEALTHY' ? 'pill ok' : h === 'FAILING' ? 'pill danger' : h === 'STALE' ? 'pill warn' : 'pill';
  }
}
