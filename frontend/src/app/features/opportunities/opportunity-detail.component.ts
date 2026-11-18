import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of, switchMap } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DateTimePipe, LabelPipe, formatClosing, formatDateTime, relativeDays, readableTitle } from '../../core/format';
import { ChangeEvent, Match, Member, OpportunityDetail, OpportunityDocument, PipelineDetail, Requirement, STAGES, Stage } from '../../core/models';
import { UiStateService } from '../../core/ui-state.service';

import { BidFormsComponent } from '../forms/bid-forms.component';

@Component({
  selector: 'app-opportunity-detail',
  imports: [BidFormsComponent, RouterLink, FormsModule, MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule, MatTabsModule, MatSelectModule,
    MatFormFieldModule, MatInputModule, MatSnackBarModule, LabelPipe, DateTimePipe, CurrencyPipe],
  templateUrl: './opportunity-detail.component.html',
  styleUrl: './opportunity-detail.component.scss',
})
export class OpportunityDetailComponent {
  protected readonly readable = readableTitle;
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);
  protected readonly auth = inject(AuthService);
  protected readonly ui = inject(UiStateService);
  readonly id = input.required<string>();
  protected readonly stages = STAGES;
  protected readonly canEditPipeline = this.auth.hasPermission('PIPELINE_EDIT');

  protected readonly data = signal<OpportunityDetail | null>(null);
  protected readonly history = signal<ChangeEvent[]>([]);
  protected readonly requirements = signal<Requirement[]>([]);
  protected readonly documents = signal<OpportunityDocument[]>([]);
  protected readonly match = signal<Match | null>(null);
  protected readonly pipeline = signal<PipelineDetail | null>(null);
  protected readonly members = signal<Member[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected noteText = '';
  protected taskTitle = '';
  protected taskDue = '';
  protected decisionRationale = '';

  protected readonly closing = computed(() => {
    const s = this.data()?.summary;
    return s ? formatClosing(s.closingDate, s.closingAt, s.closingTimeSpecified) : '';
  });
  protected readonly closingRelative = computed(() => relativeDays(this.data()?.summary.daysUntilClosing ?? null));
  protected readonly briefing = computed(() => this.data()?.briefingAt ? formatDateTime(this.data()!.briefingAt) : null);

  constructor() {
    toObservable(this.id).pipe(
      switchMap((id) => {
        this.loading.set(true);
        return forkJoin({
          detail: this.api.opportunity(id),
          history: this.api.opportunityHistory(id),
          requirements: this.api.requirements(id),
          match: this.api.match(id).pipe(catchError(() => of(null))),
          pipeline: this.api.pipelineDetail(id).pipe(catchError(() => of(null))),
        });
      }),
    ).subscribe({
      next: (r) => {
        this.data.set(r.detail);
        this.documents.set(r.detail.documents);
        this.history.set(r.history);
        this.requirements.set(r.requirements);
        this.match.set(r.match);
        this.pipeline.set(r.pipeline);
        this.loading.set(false);
      },
      error: (e) => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
    this.api.members().subscribe({ next: (m) => this.members.set(m.filter((x) => x.status === 'ACTIVE')), error: () => undefined });
  }

  // ---- documents
  protected extractDocuments(): void {
    this.busy.set('docs');
    this.api.processDocuments(this.id()).subscribe({
      next: () => {
        this.snack.open('Documents are being downloaded and read. Refresh in a minute.', 'OK', { duration: 5000 });
        setTimeout(() => this.reloadDocuments(), 15000);
        this.busy.set(null);
      },
      error: (e) => { this.error.set(errorMessage(e)); this.busy.set(null); },
    });
  }

  protected reloadDocuments(): void {
    forkJoin({ d: this.api.opportunity(this.id()), r: this.api.requirements(this.id()) }).subscribe(({ d, r }) => {
      this.data.set(d);
      this.documents.set(d.documents);
      this.requirements.set(r);
    });
  }

  protected downloadOriginal(doc: OpportunityDocument): void {
    this.api.download(`/api/v1/documents/${doc.storedDocumentId}/original`).subscribe((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = doc.title ?? 'document';
      a.click();
      URL.revokeObjectURL(url);
    });
  }

  // ---- matching / AI
  protected explain(): void {
    this.busy.set('explain');
    this.api.explainMatch(this.id()).subscribe({
      next: (x) => { this.match.update((m) => (m ? { ...m, aiExplanation: x } : m)); this.busy.set(null); },
      error: (e) => { this.error.set(errorMessage(e)); this.busy.set(null); },
    });
  }

  protected classify(): void {
    this.busy.set('classify');
    this.api.classify(this.id()).subscribe({
      next: () => { this.reloadDocuments(); this.busy.set(null); this.snack.open('AI classification added', 'OK', { duration: 3000 }); },
      error: (e) => { this.error.set(errorMessage(e)); this.busy.set(null); },
    });
  }

  protected askAi(): void {
    const d = this.data();
    if (!d) return;
    this.ui.ask(`Summarise opportunity ${d.summary.id} ("${d.summary.title}") for my company: requirements, how to submit, `
      + `key dates, and whether we should consider bidding.`);
  }

  // ---- pipeline
  protected shortlist(): void {
    this.api.shortlist(this.id()).subscribe({ next: () => this.reloadPipeline(), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected setStage(stage: Stage): void {
    this.api.setStage(this.id(), stage).subscribe({ next: () => this.reloadPipeline(), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected assign(userId: string | null): void {
    this.api.assign(this.id(), userId).subscribe({ next: () => this.reloadPipeline(), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected addNote(): void {
    if (!this.noteText.trim()) return;
    this.api.addNote(this.id(), this.noteText).subscribe({ next: () => { this.noteText = ''; this.reloadPipeline(); }, error: (e) => this.error.set(errorMessage(e)) });
  }

  protected addTask(): void {
    if (!this.taskTitle.trim()) return;
    const due = this.taskDue ? new Date(this.taskDue + 'T09:00:00+02:00').toISOString() : null;
    this.api.addTask(this.id(), { title: this.taskTitle, dueAt: due }).subscribe({
      next: () => { this.taskTitle = ''; this.taskDue = ''; this.reloadPipeline(); },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected completeTask(taskId: string, done: boolean): void {
    this.api.updateTask(taskId, done ? 'DONE' : 'OPEN').subscribe(() => this.reloadPipeline());
  }

  protected decide(decision: 'GO' | 'NO_GO'): void {
    this.api.decide(this.id(), decision, this.decisionRationale).subscribe({
      next: () => { this.decisionRationale = ''; this.reloadPipeline(); },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  private reloadPipeline(): void {
    this.api.pipelineDetail(this.id()).pipe(catchError(() => of(null))).subscribe((p) => this.pipeline.set(p));
  }

  protected scoreClass(m: Match): string {
    return { STRONG_MATCH: 'score strong', POSSIBLE_MATCH: 'score possible', REQUIRES_MANUAL_REVIEW: 'score review', WEAK_MATCH: 'score weak' }[m.label];
  }

  protected dimensionLabel(k: string): string {
    return ({ services: 'Services & products', industry: 'Industry', capability: 'Capabilities', geography: 'Geography',
      registrations: 'Registrations & certifications', other: 'Other eligibility' } as Record<string, string>)[k] ?? k;
  }

  protected percent(v: number): number {
    return Math.round(v * 100);
  }
}
