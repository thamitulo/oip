import { Component, computed, inject, signal } from '@angular/core';

import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { LabelPipe, formatClosing, relativeDays } from '../../core/format';
import { PipelineItem, Stage } from '../../core/models';

const COLUMNS: { title: string; stages: Stage[] }[] = [
  { title: 'Reviewing', stages: ['DISCOVERED', 'UNDER_REVIEW', 'SHORTLISTED'] },
  { title: 'Go / no-go', stages: ['GO_NO_GO'] },
  { title: 'Preparing bid', stages: ['DOCUMENTS_BEING_PREPARED', 'BID_IN_PROGRESS'] },
  { title: 'Submitted', stages: ['SUBMITTED'] },
  { title: 'Closed', stages: ['AWARDED', 'UNSUCCESSFUL', 'WITHDRAWN', 'EXPIRED'] },
];
const NEXT: Partial<Record<Stage, Stage[]>> = {
  DISCOVERED: ['UNDER_REVIEW', 'SHORTLISTED'], UNDER_REVIEW: ['SHORTLISTED', 'GO_NO_GO'], SHORTLISTED: ['GO_NO_GO', 'WITHDRAWN'],
  GO_NO_GO: ['DOCUMENTS_BEING_PREPARED', 'WITHDRAWN'], DOCUMENTS_BEING_PREPARED: ['BID_IN_PROGRESS', 'WITHDRAWN'],
  BID_IN_PROGRESS: ['SUBMITTED', 'WITHDRAWN'], SUBMITTED: ['AWARDED', 'UNSUCCESSFUL'],
};

@Component({
  selector: 'app-pipeline',
  imports: [RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule, LabelPipe],
  template: `
    <div class="page wide">
      <div class="page-header">
        <div><h1>Bid pipeline</h1><p>Your business's opportunities from review to award. Only your team sees this.</p></div>
        <mat-button-toggle-group [value]="mine()" (change)="mine.set($event.value)" aria-label="Filter">
          <mat-button-toggle [value]="false">Everyone</mat-button-toggle>
          <mat-button-toggle [value]="true">Assigned to me</mat-button-toggle>
        </mat-button-toggle-group>
      </div>
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      @if (!loading() && !items().length) {
        <div class="card empty">Nothing in your pipeline yet. Open an opportunity and choose <b>Add to pipeline</b>, or start from
          <a routerLink="/recommended">your recommendations</a>.</div>
      }
      <div class="board">
        @for (col of columns(); track col.title) {
          <section class="column">
            <div class="col-head"><span class="strong">{{ col.title }}</span><span class="pill">{{ col.items.length }}</span></div>
            @for (i of col.items; track i.id) {
              <article class="item">
                <a class="title" [routerLink]="['/opportunities', i.opportunityId]">{{ i.opportunity?.title }}</a>
                <div class="xs muted truncate">{{ i.opportunity?.organisationName }}</div>
                <div class="row meta">
                  <span class="pill outline">{{ i.stage | label }}</span>
                  @if (i.matchScore !== null) { <span class="pill brand">{{ i.matchScore }}%</span> }
                  @if (i.priority === 'HIGH') { <span class="pill danger">High</span> }
                  @if (i.openTasks) { <span class="pill"><mat-icon>task_alt</mat-icon>{{ i.openTasks }}</span> }
                </div>
                <div class="row xs muted">
                  <span>{{ closing(i) }}</span><span class="spacer"></span><span>{{ i.assignedToName ?? 'Unassigned' }}</span>
                </div>
                @if (canEdit && next(i.stage).length) {
                  <button mat-button class="move" [matMenuTriggerFor]="m">Move <mat-icon>expand_more</mat-icon></button>
                  <mat-menu #m="matMenu">@for (s of next(i.stage); track s) { <button mat-menu-item (click)="move(i, s)">{{ s | label }}</button> }</mat-menu>
                }
              </article>
            } @empty { <div class="xs muted pad">Empty</div> }
          </section>
        }
      </div>
    </div>
  `,
  styles: `
    .wide { max-width: none; }
    .board { display: grid; grid-template-columns: repeat(5, minmax(220px, 1fr)); gap: 10px; overflow-x: auto; padding-bottom: 8px; }
    .column { background: var(--oip-subtle); border-radius: 10px; padding: 8px; min-height: 120px; display: flex; flex-direction: column; gap: 8px; }
    .col-head { display: flex; justify-content: space-between; align-items: center; padding: 2px 4px; }
    .item { background: var(--oip-surface); border: 1px solid var(--oip-border); border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 6px; box-shadow: var(--oip-shadow); }
    .title { color: var(--oip-ink); font-weight: 500; line-height: 1.3; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
    .meta { gap: 4px; }
    .move { align-self: flex-start; margin-left: -8px; }
    .pad { padding: 8px 4px; }
  `,
})
export class PipelineComponent {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  protected readonly canEdit = this.auth.hasPermission('PIPELINE_EDIT');
  protected readonly items = signal<PipelineItem[]>([]);
  protected readonly mine = signal(false);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly columns = computed(() => {
    const me = this.auth.user()?.userId;
    const visible = this.items().filter((i) => !this.mine() || i.assignedTo === me);
    return COLUMNS.map((c) => ({ title: c.title, items: visible.filter((i) => c.stages.includes(i.stage)) }));
  });

  constructor() {
    this.reload();
  }

  protected next(stage: Stage): Stage[] {
    return NEXT[stage] ?? [];
  }

  protected closing(i: PipelineItem): string {
    const o = i.opportunity;
    if (!o) return '';
    return o.daysUntilClosing !== null && o.daysUntilClosing >= 0 ? relativeDays(o.daysUntilClosing)
      : formatClosing(o.closingDate, o.closingAt, o.closingTimeSpecified);
  }

  protected move(i: PipelineItem, stage: Stage): void {
    this.api.setStage(i.opportunityId, stage).subscribe({ next: () => this.reload(), error: (e) => this.error.set(errorMessage(e)) });
  }

  private reload(): void {
    this.api.pipeline().subscribe({
      next: (p) => { this.items.set(p); this.loading.set(false); },
      error: (e) => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
  }
}