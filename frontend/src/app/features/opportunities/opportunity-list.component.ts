import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { debounceTime, switchMap, tap } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api.service';
import { LabelPipe } from '../../core/format';
import { CATEGORIES, EVENT_TYPES, OpportunityQuery, OpportunitySummary, PROVINCES, Sector } from '../../core/models';
import { OpportunityTableComponent } from '../../shared/opportunity-table.component';

const TITLES: Record<string, { title: string; subtitle: string }> = {
  ALL: { title: 'Opportunities', subtitle: 'Tenders, RFQs, RFPs, EOIs and supplier opportunities from all sources' },
  MINING: { title: 'Mining opportunities', subtitle: 'Opportunities published by mining and resources companies' },
  GOVERNMENT: { title: 'Government tenders', subtitle: 'Public-sector opportunities, including the national eTenders portal' },
};

@Component({
  selector: 'app-opportunity-list',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatCheckboxModule, MatPaginatorModule, MatProgressBarModule, OpportunityTableComponent, LabelPipe, RouterLink],
  template: `
    <div class="page">
      <div class="page-header">
        <div><h1>{{ heading.title }}</h1><p>{{ heading.subtitle }}</p></div>
      </div>

      <form class="card filters" [formGroup]="form">
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
          <mat-label>Search</mat-label>
          <mat-icon matPrefix>search</mat-icon>
          <input matInput formControlName="q" placeholder="Keyword or reference number" />
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Type</mat-label>
          <mat-select formControlName="eventType">
            <mat-option [value]="null">Any type</mat-option>
            @for (t of eventTypes; track t) { <mat-option [value]="t">{{ t | label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Category</mat-label>
          <mat-select formControlName="procurementCategory">
            <mat-option [value]="null">Any category</mat-option>
            @for (c of categories; track c) { <mat-option [value]="c">{{ c | label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Province</mat-label>
          <mat-select formControlName="province">
            <mat-option [value]="null">Any province</mat-option>
            @for (p of provinces; track p.code) { <mat-option [value]="p.code">{{ p.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Submission</mat-label>
          <mat-select formControlName="submissionMethod">
            <mat-option [value]="null">Any</mat-option>
            <mat-option value="ELECTRONIC">Electronic</mat-option>
            <mat-option value="PHYSICAL">Physical delivery</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Closing by</mat-label>
          <input matInput type="date" formControlName="closingTo" />
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Sort</mat-label>
          <mat-select formControlName="sort">
            <mat-option value="CLOSING_SOONEST">Closing soonest</mat-option>
            <mat-option value="NEWEST">Newest discovered</mat-option>
            <mat-option value="RELEVANCE">Relevance</mat-option>
          </mat-select>
        </mat-form-field>
        <div class="toolbar">
          <span class="count">{{ total().toLocaleString('en-ZA') }} result{{ total() === 1 ? '' : 's' }}</span>
          <span class="spacer"></span>
          <mat-checkbox formControlName="noBriefing">No compulsory briefing</mat-checkbox>
          <mat-checkbox formControlName="includeClosed">Include closed</mat-checkbox>
          <button mat-button type="button" (click)="reset()"><mat-icon>restart_alt</mat-icon>Reset</button>
        </div>
      </form>
      @if (heading.title === 'Mining opportunities') {
        <div class="info-banner" style="margin:-4px 0 14px">
          Includes mining companies' own listings and public-sector tenders with a mining subject. Most mining houses procure
          through supplier registration portals: see each company's procurement channels under
          <a routerLink="/organisations" [queryParams]="{ sector: 'MINING' }">Organisations</a>.
        </div>
      }

      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (error()) { <div class="error-banner">{{ error() }}</div> }

      <app-opportunity-table [items]="items()" [emptyText]="loading() ? 'Loading…' : 'No opportunities match these filters.'" />
      <mat-paginator [length]="total()" [pageIndex]="page()" [pageSize]="pageSize" [pageSizeOptions]="[]"
        (page)="onPage($event)" aria-label="Pages" />
    </div>
  `,
  styles: `
    .filters { display: grid; grid-template-columns: minmax(200px, 1.4fr) repeat(6, minmax(136px, 1fr)); gap: 10px;
      padding: 14px 14px 6px; margin-bottom: 14px; }
    .filters mat-form-field { width: 100%; min-width: 0; }
    .toolbar { grid-column: 1 / -1; display: flex; align-items: center; flex-wrap: wrap; gap: 4px 12px;
      border-top: 1px solid var(--oip-border); margin: 4px -14px 0; padding: 4px 14px 0; }
    .count { font-weight: 600; color: var(--oip-ink-2); }
    @media (max-width: 1280px) { .filters { grid-template-columns: repeat(4, minmax(140px, 1fr)); } .filters .search { grid-column: 1 / -1; } }
    @media (max-width: 640px) { .filters { grid-template-columns: 1fr 1fr; } }
    mat-paginator { background: transparent; margin-top: 4px; }
  `,
})
export class OpportunityListComponent {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly eventTypes = EVENT_TYPES;
  protected readonly categories = CATEGORIES;
  protected readonly provinces = PROVINCES;
  protected readonly pageSize = 20;
  private readonly sector = (this.route.snapshot.data['sector'] as Sector | undefined) ?? undefined;
  protected readonly heading = TITLES[this.sector ?? 'ALL'];

  protected readonly items = signal<OpportunitySummary[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).group({
    q: [''],
    eventType: [null as string | null],
    procurementCategory: [null as string | null],
    province: [null as string | null],
    closingTo: [null as string | null],
    sort: ['CLOSING_SOONEST'],
    submissionMethod: [null as string | null],
    noBriefing: [false],
    includeClosed: [false],
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    const qp = this.route.snapshot.queryParamMap;
    this.form.patchValue({
      q: qp.get('q') ?? '',
      eventType: qp.get('eventType'),
      procurementCategory: qp.get('procurementCategory'),
      province: qp.get('province'),
      closingTo: qp.get('closingTo'),
      sort: qp.get('sort') ?? 'CLOSING_SOONEST',
      includeClosed: qp.get('includeClosed') === 'true',
      submissionMethod: qp.get('submissionMethod'),
      noBriefing: qp.get('noBriefing') === 'true',
    }, { emitEvent: false });
    this.page.set(Number(qp.get('page') ?? 0));

    this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed(destroyRef)).subscribe(() => {
      this.page.set(0);
      this.syncUrl();
    });
    this.route.queryParamMap.pipe(
      tap(() => { this.loading.set(true); this.error.set(null); }),
      switchMap(() => this.api.opportunities(this.query())),
      takeUntilDestroyed(destroyRef),
    ).subscribe({
      next: (p) => { this.items.set(p.content); this.total.set(p.totalElements); this.loading.set(false); },
      error: (e) => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
  }

  protected onPage(e: PageEvent): void {
    this.page.set(e.pageIndex);
    this.syncUrl();
    window.scrollTo({ top: 0 });
  }

  protected reset(): void {
    this.form.reset({ q: '', sort: 'CLOSING_SOONEST', includeClosed: false, noBriefing: false });
  }

  private query(): OpportunityQuery {
    const v = this.form.getRawValue();
    return {
      q: v.q || undefined,
      sector: this.sector,
      eventType: (v.eventType ?? undefined) as OpportunityQuery['eventType'],
      procurementCategory: (v.procurementCategory ?? undefined) as OpportunityQuery['procurementCategory'],
      province: v.province ?? undefined,
      closingTo: v.closingTo ?? undefined,
      activeOnly: v.includeClosed ? false : undefined,
      sort: (v.sort ?? undefined) as OpportunityQuery['sort'],
      submissionMethod: (v.submissionMethod ?? undefined) as OpportunityQuery['submissionMethod'],
      compulsoryBriefing: v.noBriefing ? false : undefined,
      page: this.page(),
      size: this.pageSize,
    };
  }

  private syncUrl(): void {
    const v = this.form.getRawValue();
    void this.router.navigate([], {
      relativeTo: this.route,
      replaceUrl: true,
      queryParams: {
        q: v.q || null, eventType: v.eventType, procurementCategory: v.procurementCategory, province: v.province,
        closingTo: v.closingTo, sort: v.sort === 'CLOSING_SOONEST' ? null : v.sort,
        includeClosed: v.includeClosed ? true : null, page: this.page() || null,
        submissionMethod: v.submissionMethod, noBriefing: v.noBriefing ? true : null,
      },
    });
  }
}
