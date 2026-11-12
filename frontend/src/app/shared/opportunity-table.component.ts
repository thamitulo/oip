import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { LabelPipe, closingParts, humanise, readableTitle } from '../core/format';
import { MatchLabel, OpportunitySummary } from '../core/models';

export interface OpportunityRow {
  opportunity: OpportunitySummary;
  score?: number | null;
  label?: MatchLabel | null;
}

interface ViewRow {
  o: OpportunitySummary;
  title: string;
  place: string;
  date: string;
  time: string | null;
  urgencyText: string;
  urgencyClass: string;
  score: number | null;
  scoreClass: string;
  scoreLabel: string;
}

/**
 * The opportunity list as a table: closing date first (what people scan for), then the opportunity, buyer,
 * type, location and how bids are submitted. On narrow screens each row becomes a compact card.
 */
@Component({
  selector: 'app-opportunity-table',
  imports: [RouterLink, MatIconModule, MatTooltipModule, LabelPipe],
  template: `
    <div class="table-wrap">
      <table class="otable" [class.compact]="compact()">
        <thead>
          <tr>
            @if (showScore()) { <th class="c-score">Match</th> }
            <th class="c-close">Closes</th>
            <th class="c-title">Opportunity</th>
            @if (!compact()) {
              <th class="c-type">Type</th>
              <th class="c-loc">Location</th>
            }
            <th class="c-sub">Submission</th>
          </tr>
        </thead>
        <tbody>
          @for (r of view(); track r.o.id) {
            <tr [routerLink]="['/opportunities', r.o.id]" tabindex="0" (keydown.enter)="$any($event.currentTarget).click()">
              @if (showScore()) {
                <td class="c-score"><span [class]="r.scoreClass" [matTooltip]="r.scoreLabel + ' (indicative, not eligibility)'">{{ r.score ?? '—' }}</span></td>
              }
              <td class="c-close">
                <div class="date">{{ r.date }}</div>
                <div class="sub">
                  @if (r.time) { <span class="time">{{ r.time }}</span> }
                  <span [class]="r.urgencyClass">{{ r.urgencyText }}</span>
                </div>
              </td>
              <td class="c-title">
                <a class="title" [routerLink]="['/opportunities', r.o.id]" [attr.title]="r.o.title" (click)="$event.stopPropagation()">{{ r.title }}</a>
                <div class="meta">
                  <span class="buyer">{{ r.o.organisationName ?? 'Buyer not specified' }}</span>
                  @if (r.o.referenceNumber) { <span class="sep">·</span><span class="ref">{{ r.o.referenceNumber }}</span> }
                  @if (compact()) { <span class="sep">·</span><span>{{ r.o.eventType | label }}</span> }
                  @if (r.o.documentCount) {
                    <span class="sep">·</span>
                    <span class="docs" [matTooltip]="r.o.documentCount + ' document' + (r.o.documentCount === 1 ? '' : 's')">
                      <mat-icon>description</mat-icon>{{ r.o.documentCount }}</span>
                  }
                </div>
                @if (r.o.briefingCompulsory || r.o.miningRelated || r.o.sector === 'MINING' || !r.o.sourceVerified) {
                  <div class="flags">
                    @if (r.o.briefingCompulsory) { <span class="pill danger"><mat-icon>groups</mat-icon>Compulsory briefing</span> }
                    @if (r.o.sector === 'MINING') { <span class="pill brand"><mat-icon>landscape</mat-icon>Mining company</span> }
                    @else if (r.o.miningRelated) { <span class="pill brand"><mat-icon>landscape</mat-icon>Mining-related</span> }
                    @if (!r.o.sourceVerified) { <span class="pill warn">Unverified source</span> }
                  </div>
                }
              </td>
              @if (!compact()) {
                <td class="c-type"><span class="pill outline">{{ r.o.eventType | label }}</span></td>
                <td class="c-loc">{{ r.place }}</td>
              }
              <td class="c-sub">
                @switch (r.o.submissionMethod) {
                  @case ('ELECTRONIC') { <span class="method e"><mat-icon>cloud_upload</mat-icon>Electronic</span> }
                  @case ('PHYSICAL') { <span class="method p"><mat-icon>local_shipping</mat-icon>Physical</span> }
                  @case ('ELECTRONIC_OR_PHYSICAL') { <span class="method e"><mat-icon>swap_horiz</mat-icon>Either</span> }
                  @default { <span class="method n">Not stated</span> }
                }
              </td>
            </tr>
          } @empty {
            <tr class="empty-row"><td [attr.colspan]="columns()">{{ emptyText() }}</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    :host { display: block; }
    .table-wrap { background: var(--oip-surface); border: 1px solid var(--oip-border); border-radius: var(--oip-radius);
      box-shadow: var(--oip-shadow); overflow: hidden; }
    .otable { width: 100%; border-collapse: collapse; table-layout: fixed; }
    th { text-align: left; font-size: 11px; font-weight: 600; letter-spacing: .05em; text-transform: uppercase; color: var(--oip-muted);
      padding: 10px 14px; background: #fafbfc; border-bottom: 1px solid var(--oip-border); white-space: nowrap; }
    td { padding: 12px 14px; border-bottom: 1px solid var(--oip-border); vertical-align: top; }
    tbody tr:last-child td { border-bottom: none; }
    tbody tr:not(.empty-row) { cursor: pointer; transition: background .12s; }
    tbody tr:not(.empty-row):hover td { background: #f8fafc; }
    tbody tr:not(.empty-row):hover .title { color: var(--oip-brand); }
    tbody tr:focus-visible { outline: 2px solid var(--oip-brand); outline-offset: -2px; }

    .c-score { width: 64px; }
    .c-close { width: 128px; }
    .c-type { width: 110px; }
    .c-loc { width: 130px; color: var(--oip-ink-2); }
    .c-sub { width: 118px; }

    .date { font-weight: 600; color: var(--oip-ink); white-space: nowrap; font-variant-numeric: tabular-nums; }
    .sub { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 6px; margin-top: 4px; }
    .time { color: var(--oip-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
    .sub .pill { height: 18px; font-size: 11px; padding: 0 7px; }

    .title { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; color: var(--oip-ink);
      font-weight: 500; font-size: 13.5px; line-height: 1.4; text-decoration: none; }
    .title:hover { text-decoration: none; }
    .meta { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 6px; margin-top: 4px; color: var(--oip-muted); font-size: 12px; }
    .buyer { color: var(--oip-ink-2); font-weight: 500; }
    .ref { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11.5px; }
    .sep { color: var(--oip-border-strong); }
    .flags { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 8px; }
    .docs { display: inline-flex; align-items: center; gap: 2px; color: var(--oip-muted); font-size: 12px; align-self: center; }
    .docs mat-icon { font-size: 14px; width: 14px; height: 14px; }

    .method { display: inline-flex; align-items: center; gap: 5px; font-size: 12.5px; font-weight: 500; white-space: nowrap; }
    .method mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .method.e { color: var(--oip-info); }
    .method.p { color: var(--oip-warn); }
    .method.n { color: var(--oip-muted); font-weight: 400; }
    .score { min-width: 36px; }

    .empty-row td { text-align: center; color: var(--oip-muted); padding: 36px 16px; cursor: default; }

    .compact td { padding: 10px 12px; }
    .compact th { padding: 9px 12px; }
    .compact .c-close { width: 112px; }
    .compact .c-sub { width: 104px; }

    @media (max-width: 1000px) { .c-loc { display: none; } }
    @media (max-width: 760px) {
      .otable, .otable tbody, .otable tr, .otable td { display: block; width: auto; }
      .otable thead { display: none; }
      .otable tr { padding: 12px 14px; border-bottom: 1px solid var(--oip-border); }
      .otable td { border: none; padding: 0; }
      .otable td.c-title { margin: 6px 0; }
      .c-close { display: flex !important; gap: 8px; align-items: center; }
      .c-close .sub { margin: 0; }
      .otable td.c-type, .otable td.c-loc, .otable td.c-sub, .otable td.c-score { display: inline-flex; align-items: center;
        vertical-align: middle; margin-right: 12px; }
      .otable td.c-loc::before { content: '⌖'; color: var(--oip-muted); margin-right: 4px; }
    }
  `,
})
export class OpportunityTableComponent {
  /** Plain opportunities, or {@link rows} when a match score is shown. */
  readonly items = input<OpportunitySummary[] | null>(null);
  readonly rows = input<OpportunityRow[] | null>(null);
  readonly showScore = input(false);
  readonly compact = input(false);
  readonly emptyText = input('No opportunities match.');

  protected readonly columns = computed(() => (this.showScore() ? 1 : 0) + (this.compact() ? 3 : 5));

  protected readonly view = computed<ViewRow[]>(() => (this.rows() ?? (this.items() ?? []).map((opportunity) => ({ opportunity } as OpportunityRow))).map(({ opportunity: o, score, label }) => {
    const parts = closingParts(o.closingDate, o.closingAt, o.closingTimeSpecified);
    const open = o.status === 'OPEN' || o.status === 'UNKNOWN';
    return {
      o,
      title: readableTitle(o.title),
      place: o.province ? humanise(o.province) : tidyPlace(o.locality),
      date: parts.date,
      time: parts.time,
      urgencyText: open ? shortDays(o.daysUntilClosing) : humanise(o.status),
      urgencyClass: !open ? 'pill' : ({ CLOSING_TODAY: 'pill danger', URGENT: 'pill danger', SOON: 'pill warn', OPEN: 'pill ok' } as Record<string, string>)[o.urgency] ?? 'pill',
      score: score ?? null,
      scoreLabel: humanise(label ?? null),
      scoreClass: ({ STRONG_MATCH: 'score strong', POSSIBLE_MATCH: 'score possible', REQUIRES_MANUAL_REVIEW: 'score review' } as Record<string, string>)[label ?? ''] ?? 'score weak',
    };
  }));
}

/** Locality as published, minus surrounding brackets and shouting capitals. */
function tidyPlace(place: string | null): string {
  if (!place) return '—';
  const p = place.trim().replace(/^\((.*)\)$/, '$1').trim();
  return p === p.toUpperCase() ? p.toLowerCase().replace(/(^|[\s-])([a-z])/g, (_m, a: string, c: string) => a + c.toUpperCase()) : p;
}

function shortDays(days: number | null): string {
  if (days === null) return 'No date';
  if (days < 0) return 'Closed';
  if (days === 0) return 'Today';
  if (days === 1) return 'Tomorrow';
  return `${days} days`;
}
