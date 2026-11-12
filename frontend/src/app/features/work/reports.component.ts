import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api.service';

import { Bucket } from '../../core/models';
import { BarListComponent } from '../../shared/bar-list.component';

interface Week { week: string; government: number; mining: number; total: number; }

@Component({
  selector: 'app-reports',
  imports: [BarListComponent, CurrencyPipe],
  template: `
    <div class="page">
      <div class="page-header"><div><h1>Reports</h1><p>Your bid performance and the market, from recorded data.</p></div></div>
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      @if (report(); as r) {
        <div class="grid grid-stats" style="margin-bottom:12px">
          <div class="stat"><div class="value">{{ total() }}</div><div class="label">In pipeline</div></div>
          <div class="stat"><div class="value">{{ r.outcomes['submitted'] ?? 0 }}</div><div class="label">Bids submitted</div></div>
          <div class="stat"><div class="value">{{ r.outcomes['awarded'] ?? 0 }}</div><div class="label">Awarded</div></div>
          <div class="stat"><div class="value">{{ r.outcomes['winRate'] === null ? '—' : r.outcomes['winRate'] + '%' }}</div><div class="label">Win rate (decided bids)</div></div>
          <div class="stat"><div class="value">{{ (r.outcomes['awarded_value'] ?? 0) | currency: 'ZAR' : 'symbol-narrow' : '1.0-0' }}</div><div class="label">Awarded value</div></div>
          <div class="stat"><div class="value">{{ r.tasks['overdue'] ?? 0 }}</div><div class="label">Overdue tasks</div></div>
        </div>
        <div class="grid grid-2">
          <section class="card"><h2>Pipeline by stage</h2><app-bar-list [items]="stageBuckets()" /></section>
          <section class="card"><h2>Active opportunities by category</h2><app-bar-list [items]="categoryBuckets()" [humaniseLabels]="false" /></section>
          <section class="card">
            <h2>Opportunities discovered per week</h2>
            <div class="chart" role="img" [attr.aria-label]="'Weekly discovered opportunities'">
              @for (w of weeks(); track w.week) {
                <div class="col" [title]="w.week + ': ' + w.government + ' government, ' + w.mining + ' mining'">
                  <div class="stackbar">
                    <span class="seg gov" [style.height.%]="pct(w.government)"></span>
                    <span class="seg min" [style.height.%]="pct(w.mining)"></span>
                  </div>
                  <span class="xs muted">{{ w.week.slice(5) }}</span>
                </div>
              } @empty { <div class="muted small">No data yet.</div> }
            </div>
            <div class="row xs muted"><span class="key gov"></span>Government <span class="key min"></span>Mining</div>
          </section>
        </div>
      }
    </div>
  `,
  styles: `
    .chart { display: flex; align-items: flex-end; gap: 6px; height: 160px; padding-top: 8px; }
    .col { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; height: 100%; }
    .stackbar { flex: 1; width: 100%; max-width: 28px; display: flex; flex-direction: column-reverse; gap: 1px; }
    .seg { display: block; border-radius: 3px 3px 0 0; min-height: 0; }
    .seg.gov, .key.gov { background: var(--oip-brand); }
    .seg.min, .key.min { background: #d97706; }
    .key { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-left: 8px; }
  `,
})
export class ReportsComponent {
  private readonly api = inject(ApiService);
  protected readonly report = signal<{ byStage: { stage: string; count: number }[]; outcomes: Record<string, number | null>; tasks: Record<string, number> } | null>(null);
  protected readonly weeks = signal<Week[]>([]);
  protected readonly categories = signal<{ category: string; count: number }[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly total = computed(() => (this.report()?.byStage ?? []).reduce((a, b) => a + Number(b.count), 0));
  protected readonly stageBuckets = computed<Bucket[]>(() => (this.report()?.byStage ?? []).map((s) => ({ key: s.stage, label: s.stage, count: Number(s.count) })));
  protected readonly categoryBuckets = computed<Bucket[]>(() => this.categories().slice(0, 12).map((c) => ({ key: c.category, label: c.category, count: Number(c.count) })));
  private readonly max = computed(() => Math.max(1, ...this.weeks().map((w) => Number(w.total))));

  constructor() {
    forkJoin({ p: this.api.pipelineReport(), w: this.api.weeklyOpportunities(12), c: this.api.categoryReport() }).subscribe({
      next: ({ p, w, c }) => { this.report.set(p); this.weeks.set(w); this.categories.set(c); },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected pct(v: number): number {
    return (Number(v) / this.max()) * 100;
  }
}
