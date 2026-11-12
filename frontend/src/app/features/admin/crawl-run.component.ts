import { Component, inject, input, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api.service';
import { DateTimePipe, LabelPipe } from '../../core/format';
import { CrawlRunDetail } from '../../core/models';

@Component({
  selector: 'app-crawl-run',
  imports: [RouterLink, MatButtonModule, MatIconModule, LabelPipe, DateTimePipe],
  template: `
    <div class="page">
      <a mat-button routerLink="/admin/crawls"><mat-icon>arrow_back</mat-icon>Crawl monitoring</a>
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      @if (data(); as d) {
        <div class="page-header" style="margin-top:8px">
          <div><h1>{{ d.run.sourceName }}</h1><p>{{ d.run.startedAt | dateTime }} · {{ d.run.trigger | label }}</p></div>
          <a mat-stroked-button [routerLink]="['/admin/sources', d.run.sourceId]">Source settings</a>
        </div>
        <div class="grid grid-stats" style="margin-bottom:16px">
          <div class="stat"><div class="value" style="font-size:20px">{{ d.run.outcome | label }}</div><div class="label">Outcome</div></div>
          <div class="stat"><div class="value">{{ d.run.recordsFound }}</div><div class="label">Found</div></div>
          <div class="stat"><div class="value">{{ d.run.recordsDiscovered }}</div><div class="label">New</div></div>
          <div class="stat"><div class="value">{{ d.run.recordsUpdated }}</div><div class="label">Updated</div></div>
          <div class="stat"><div class="value">{{ d.run.recordsUnchanged }}</div><div class="label">Unchanged</div></div>
          <div class="stat"><div class="value">{{ d.run.recordsRejected }}</div><div class="label">Rejected</div></div>
        </div>
        @if (d.run.message) { <div class="warn-banner" style="margin-bottom:16px">{{ d.run.message }}</div> }
        <div class="stack">
          <section class="card">
            <h2>Errors ({{ d.errors.length }})</h2>
            @for (e of d.errors; track e.id) {
              <div style="padding:6px 0;border-bottom:1px solid var(--oip-border)">
                <span class="pill danger">{{ e.errorType | label }}</span> {{ e.message }}
                @if (e.url) { <div class="mono muted">{{ e.url }}</div> }
              </div>
            } @empty { <div class="muted">No errors.</div> }
          </section>
          <section class="card">
            <h2>Fetched evidence ({{ d.observations.length }})</h2>
            @for (o of d.observations; track o.id) {
              <details style="padding:6px 0;border-bottom:1px solid var(--oip-border)">
                <summary><span class="mono">{{ o.url }}</span> · HTTP {{ o.httpStatus }} · {{ o.bytes }} bytes · {{ o.fetchedAt | dateTime }}</summary>
                <div class="muted small">SHA-256 {{ o.contentHash }} · {{ o.contentType }}</div>
                <pre class="mono" style="white-space:pre-wrap;max-height:240px;overflow:auto;background:var(--oip-neutral-bg);padding:8px;border-radius:6px">{{ o.excerpt }}</pre>
              </details>
            } @empty { <div class="muted">Nothing was fetched.</div> }
          </section>
          <section class="card">
            <h2>Execution log</h2>
            <pre class="mono" style="white-space:pre-wrap;margin:0">{{ d.executionLog || '—' }}</pre>
          </section>
        </div>
      }
    </div>
  `,
})
export class CrawlRunComponent {
  private readonly api = inject(ApiService);
  readonly id = input.required<string>();
  protected readonly data = signal<CrawlRunDetail | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    toObservable(this.id).pipe(switchMap((id) => this.api.crawlRun(id))).subscribe({
      next: (d) => this.data.set(d),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }
}
