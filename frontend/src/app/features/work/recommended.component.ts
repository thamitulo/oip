import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSliderModule } from '@angular/material/slider';
import { RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { Recommended } from '../../core/models';
import { UiStateService } from '../../core/ui-state.service';
import { OpportunityTableComponent } from '../../shared/opportunity-table.component';

@Component({
  selector: 'app-recommended',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatPaginatorModule, MatProgressBarModule, MatSliderModule, OpportunityTableComponent],
  template: `
    <div class="page">
      <div class="page-header">
        <div><h1>Recommended for your company</h1>
          <p>Active opportunities ranked by an explainable match with your company profile. Scores are indicative — they never mean you are eligible.</p></div>
        <div class="row">
          <span class="small muted">Minimum score {{ minScore }}</span>
          <mat-slider min="0" max="90" step="5" discrete style="width:160px"><input matSliderThumb [(ngModel)]="minScore" (valueChange)="reload(0)" /></mat-slider>
          <a mat-stroked-button routerLink="/settings"><mat-icon>tune</mat-icon>Weights</a>
          @if (ui.aiStatus()?.assistantAvailable) {
            <button mat-stroked-button (click)="ui.ask('Which open opportunities best match my company, and why?')"><mat-icon>auto_awesome</mat-icon>Ask AI</button>
          }
        </div>
      </div>
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      @if (!loading() && items().length === 0) {
        <div class="card empty">No matches at this score. Add services, products, industries, capabilities and regions to your
          <a routerLink="/profile">company profile</a>; matching uses them.</div>
      } @else {
        <app-opportunity-table [rows]="items()" [showScore]="true" />
      }
      <mat-paginator [length]="total()" [pageIndex]="page()" [pageSize]="20" [pageSizeOptions]="[]" (page)="onPage($event)" />
    </div>
  `,
})
export class RecommendedComponent {
  private readonly api = inject(ApiService);
  protected readonly ui = inject(UiStateService);
  protected minScore = 40;
  protected readonly items = signal<Recommended[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.reload(0);
  }

  protected onPage(e: PageEvent): void {
    this.reload(e.pageIndex);
  }

  protected reload(page: number): void {
    this.page.set(page);
    this.loading.set(true);
    this.api.recommended(this.minScore, page).subscribe({
      next: (p) => { this.items.set(p.content); this.total.set(p.totalElements); this.loading.set(false); },
      error: (e) => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
  }
}
