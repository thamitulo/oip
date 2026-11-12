import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api.service';
import { DateTimePipe, LabelPipe } from '../../core/format';
import { Dashboard, OpportunitySummary } from '../../core/models';
import { BarListComponent } from '../../shared/bar-list.component';
import { OpportunityTableComponent } from '../../shared/opportunity-table.component';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MatIconModule, MatButtonModule, MatProgressBarModule, BarListComponent, OpportunityTableComponent,
    DateTimePipe, LabelPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div>
          <h1>Dashboard</h1>
          <p>Live figures from crawled sources@if (data(); as d) { · last successful crawl {{ d.lastSuccessfulCrawlAt | dateTime }}}</p>
        </div>
        <a mat-stroked-button routerLink="/opportunities"><mat-icon>search</mat-icon>Browse opportunities</a>
      </div>
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (error()) { <div class="error-banner">{{ error() }}</div> }

      @if (data(); as d) {
        @if (d.activeOpportunities === 0) {
          <div class="info-banner" style="margin-bottom:16px">
            No active opportunities yet. Figures appear once enabled sources have been crawled.
          </div>
        }
        <div class="grid grid-stats" style="margin-bottom:16px">
          <a class="stat" routerLink="/opportunities"><div class="value">{{ d.activeOpportunities.toLocaleString('en-ZA') }}</div><div class="label">Active opportunities</div></a>
          <div class="stat"><div class="value">{{ d.newToday.toLocaleString('en-ZA') }}</div><div class="label">New today</div></div>
          <div class="stat"><div class="value">{{ d.closingWithin7Days.toLocaleString('en-ZA') }}</div><div class="label">Closing within 7 days</div></div>
          <a class="stat" routerLink="/mining"><div class="value">{{ d.miningActive.toLocaleString('en-ZA') }}</div><div class="label">Mining</div></a>
          <a class="stat" routerLink="/government"><div class="value">{{ d.governmentActive.toLocaleString('en-ZA') }}</div><div class="label">Government</div></a>
          <div class="stat"><div class="value">{{ d.sourcesHealthy }}/{{ d.sourcesEnabled }}</div><div class="label">Sources healthy</div></div>
        </div>

        <section class="block">
          <div class="block-head">
            <h2>Closing soon</h2>
            <a mat-button routerLink="/opportunities">View all<mat-icon iconPositionEnd>arrow_forward</mat-icon></a>
          </div>
          <app-opportunity-table [items]="closingSoon()" emptyText="Nothing closes in the next 7 days." />
        </section>

        <div class="grid charts">
            <section class="card"><h2>By province</h2><app-bar-list [items]="d.byProvince" /></section>
            <section class="card"><h2>By procurement category</h2><app-bar-list [items]="d.byCategory" /></section>
            <section class="card"><h2>By type</h2><app-bar-list [items]="d.byEventType" /></section>
            <section class="card"><h2>How bids are submitted</h2><app-bar-list [items]="d.bySubmissionMethod" /></section>
            <section class="card"><h2>Top organisations</h2><app-bar-list [items]="d.topOrganisations" [humaniseLabels]="false" /></section>
            <section class="card">
              <h2>Recent source updates</h2>
              @for (u of d.recentSourceUpdates; track $index) {
                <div class="row" style="justify-content:space-between;padding:6px 0;border-bottom:1px solid var(--oip-border)">
                  <span>{{ u.sourceName }}</span>
                  <span class="muted small">{{ u.outcome | label }} · +{{ u.recordsDiscovered }} new · {{ u.finishedAt | dateTime }}</span>
                </div>
              } @empty { <div class="muted small">No crawls have run yet.</div> }
            </section>
        </div>
      }
    </div>
  `,
  styles: `
    .block { margin-bottom: 20px; }
    .block-head { display: flex; align-items: center; justify-content: space-between; margin: 0 2px 8px; }
    .block-head h2 { font-size: 15px; font-weight: 600; margin: 0; }
    .charts { grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); align-items: start; }
  `,
})
export class DashboardComponent {
  private readonly api = inject(ApiService);
  protected readonly data = signal<Dashboard | null>(null);
  protected readonly closingSoon = signal<OpportunitySummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    forkJoin({ dashboard: this.api.dashboard(), soon: this.api.closingSoon(7, undefined, 8) }).subscribe({
      next: ({ dashboard, soon }) => {
        this.data.set(dashboard);
        this.closingSoon.set(soon.content);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }
}
