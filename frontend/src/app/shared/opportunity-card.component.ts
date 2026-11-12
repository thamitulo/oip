import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { LabelPipe, formatClosing, humanise, relativeDays } from '../core/format';
import { MatchLabel, OpportunitySummary } from '../core/models';

/** Compact list row for an opportunity. Use inside a `.list` container. */
@Component({
  selector: 'app-opportunity-card',
  imports: [RouterLink, MatIconModule, MatTooltipModule, LabelPipe],
  template: `
    @let o = opportunity();
    <a class="row-item" [routerLink]="['/opportunities', o.id]">
      @if (score() !== null) {
        <span class="score" [class]="scoreClass()" [matTooltip]="(matchLabel() | label) + ' — indicative, not eligibility'">{{ score() }}</span>
      }
      <div class="main">
        <div class="title">{{ o.title }}</div>
        <div class="meta">
          <span class="org">{{ o.organisationName ?? 'Organisation not specified' }}</span>
          @if (o.referenceNumber) { <span class="mono">{{ o.referenceNumber }}</span> }
          @if (o.province || o.locality) { <span><mat-icon>place</mat-icon>{{ o.province ? (o.province | label) : o.locality }}</span> }
          @if (o.documentCount) { <span><mat-icon>description</mat-icon>{{ o.documentCount }}</span> }
        </div>
        <div class="tags">
          <span class="pill outline">{{ o.eventType | label }}</span>
          @switch (o.submissionMethod) {
            @case ('ELECTRONIC') { <span class="pill info" matTooltip="Bids are submitted electronically"><mat-icon>cloud_upload</mat-icon>Electronic</span> }
            @case ('PHYSICAL') { <span class="pill warn" matTooltip="Bids are delivered physically (tender box / hand delivery)"><mat-icon>local_shipping</mat-icon>Physical</span> }
            @case ('ELECTRONIC_OR_PHYSICAL') { <span class="pill info"><mat-icon>swap_horiz</mat-icon>Electronic or physical</span> }
            @default { <span class="pill" matTooltip="The source does not say how to submit">Submission not stated</span> }
          }
          @if (o.briefingCompulsory) { <span class="pill danger"><mat-icon>groups</mat-icon>Compulsory briefing</span> }
          @if (o.sector === 'MINING') { <span class="pill brand"><mat-icon>landscape</mat-icon>Mining company</span> }
          @else if (o.miningRelated) { <span class="pill brand" matTooltip="Public-sector tender with a mining subject"><mat-icon>landscape</mat-icon>Mining-related</span> }
          @if (!o.sourceVerified) { <span class="pill warn">Unverified source</span> }
        </div>
      </div>
      <div class="closing">
        <span class="pill" [class]="urgencyClass()">{{ urgencyText() }}</span>
        <span class="xs muted nowrap">{{ closing() }}</span>
      </div>
    </a>
  `,
  styles: `
    .row-item { display: flex; gap: 12px; align-items: flex-start; padding: 11px 14px; color: inherit; text-decoration: none; background: var(--oip-surface); }
    .row-item:hover { background: #fafbfc; text-decoration: none; }
    .row-item:hover .title { color: var(--oip-brand); }
    .main { flex: 1; min-width: 0; }
    .title { font-weight: 500; line-height: 1.35; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .meta { display: flex; flex-wrap: wrap; gap: 2px 12px; color: var(--oip-muted); font-size: 12px; margin-top: 3px; }
    .meta span { display: inline-flex; align-items: center; gap: 3px; }
    .meta mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .org { color: var(--oip-ink-2); font-weight: 500; }
    .tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
    .closing { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; min-width: 120px; text-align: right; }
    .score { margin-top: 1px; }
    @media (max-width: 640px) { .row-item { flex-wrap: wrap; } .closing { align-items: flex-start; min-width: 0; } }
  `,
})
export class OpportunityCardComponent {
  readonly opportunity = input.required<OpportunitySummary>();
  readonly score = input<number | null>(null);
  readonly matchLabel = input<MatchLabel | null>(null);

  protected readonly closing = computed(() => {
    const o = this.opportunity();
    return formatClosing(o.closingDate, o.closingAt, o.closingTimeSpecified);
  });
  protected readonly urgencyText = computed(() => {
    const o = this.opportunity();
    if (o.status !== 'OPEN' && o.status !== 'UNKNOWN') return humanise(o.status);
    return relativeDays(o.daysUntilClosing);
  });
  protected readonly urgencyClass = computed(() => {
    switch (this.opportunity().urgency) {
      case 'CLOSING_TODAY':
      case 'URGENT': return 'pill danger';
      case 'SOON': return 'pill warn';
      case 'OPEN': return 'pill ok';
      default: return 'pill';
    }
  });
  protected readonly scoreClass = computed(() => {
    switch (this.matchLabel()) {
      case 'STRONG_MATCH': return 'score strong';
      case 'POSSIBLE_MATCH': return 'score possible';
      case 'REQUIRES_MANUAL_REVIEW': return 'score review';
      default: return 'score weak';
    }
  });
}
