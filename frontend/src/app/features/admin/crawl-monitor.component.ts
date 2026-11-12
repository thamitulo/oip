import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { DateTimePipe, LabelPipe } from '../../core/format';
import { CrawlError, CrawlRun } from '../../core/models';

@Component({
  selector: 'app-crawl-monitor',
  imports: [RouterLink, MatTabsModule, MatButtonModule, MatIconModule, MatPaginatorModule, LabelPipe, DateTimePipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div><h1>Crawl monitoring</h1><p>Crawl history and errors. Failed crawls are never counted as "no opportunities".</p></div>
        <button mat-stroked-button (click)="reload()"><mat-icon>refresh</mat-icon>Refresh</button>
      </div>
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      <mat-tab-group>
        <mat-tab label="Crawl history">
          <div class="card table-scroll" style="padding:0;margin-top:12px">
            <table class="data">
              <thead><tr><th>Started</th><th>Source</th><th>Trigger</th><th>Outcome</th><th>Found</th><th>New</th><th>Updated</th><th>Rejected</th><th>Duration</th></tr></thead>
              <tbody>
                @for (r of runs(); track r.id) {
                  <tr class="clickable" [routerLink]="['/admin/crawls', r.id]">
                    <td class="small">{{ r.startedAt | dateTime }}</td><td>{{ r.sourceName }}</td><td>{{ r.trigger | label }}</td>
                    <td><span class="pill" [class.ok]="r.status === 'SUCCEEDED'" [class.danger]="r.status === 'FAILED'">{{ r.outcome ? (r.outcome | label) : 'Running' }}</span></td>
                    <td>{{ r.recordsFound }}</td><td>{{ r.recordsDiscovered }}</td><td>{{ r.recordsUpdated }}</td><td>{{ r.recordsRejected }}</td>
                    <td class="small">{{ duration(r) }}</td>
                  </tr>
                } @empty { <tr><td colspan="9" class="empty">No crawls have run yet.</td></tr> }
              </tbody>
            </table>
          </div>
          <mat-paginator [length]="runTotal()" [pageSize]="50" [pageSizeOptions]="[]" (page)="runPage($event)" />
        </mat-tab>
        <mat-tab label="Errors">
          <div class="card table-scroll" style="padding:0;margin-top:12px">
            <table class="data">
              <thead><tr><th>When</th><th>Source</th><th>Type</th><th>Message</th></tr></thead>
              <tbody>
                @for (e of errors(); track e.id) {
                  <tr class="clickable" [routerLink]="['/admin/crawls', e.runId]">
                    <td class="small">{{ e.occurredAt | dateTime }}</td><td>{{ e.sourceName }}</td>
                    <td><span class="pill danger">{{ e.errorType | label }}</span></td>
                    <td class="small">{{ e.message }}@if (e.itemReference) { <span class="muted"> ({{ e.itemReference }})</span> }</td>
                  </tr>
                } @empty { <tr><td colspan="4" class="empty">No errors recorded.</td></tr> }
              </tbody>
            </table>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
})
export class CrawlMonitorComponent {
  private readonly api = inject(ApiService);
  protected readonly runs = signal<CrawlRun[]>([]);
  protected readonly runTotal = signal(0);
  protected readonly errors = signal<CrawlError[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.reload();
  }

  protected reload(page = 0): void {
    this.api.crawlRuns({ page, size: 50 }).subscribe({
      next: (p) => { this.runs.set(p.content); this.runTotal.set(p.totalElements); },
      error: (e) => this.error.set(errorMessage(e)),
    });
    this.api.sourceErrors({ size: 100 }).subscribe((p) => this.errors.set(p.content));
  }

  protected runPage(e: PageEvent): void {
    this.reload(e.pageIndex);
  }

  protected duration(r: CrawlRun): string {
    if (!r.finishedAt) return '—';
    const s = Math.round((Date.parse(r.finishedAt) - Date.parse(r.startedAt)) / 1000);
    return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
  }
}
