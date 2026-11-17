import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { OpportunitySummary } from '../../core/models';
import { OpportunityTableComponent } from '../../shared/opportunity-table.component';
import { FocusArea, FocusRequest, FocusService, FocusTemplate } from './focus.service';

const PROVINCES = [
  ['EASTERN_CAPE', 'Eastern Cape'], ['FREE_STATE', 'Free State'], ['GAUTENG', 'Gauteng'], ['KWAZULU_NATAL', 'KwaZulu-Natal'],
  ['LIMPOPO', 'Limpopo'], ['MPUMALANGA', 'Mpumalanga'], ['NORTH_WEST', 'North West'], ['NORTHERN_CAPE', 'Northern Cape'],
  ['WESTERN_CAPE', 'Western Cape'],
] as const;
const splitList = (s: string): string[] => s.split(',').map((k) => k.trim()).filter((k) => k.length > 0);
const EVENT_TYPES = ['LAND_LEASE', 'TENDER', 'RFQ', 'RFP', 'RFI', 'EOI', 'ESD_PROGRAMME', 'OTHER'];

/** "My focus": what the user is currently looking for (e.g. state land to lease), with matching opportunities. */
@Component({
  selector: 'app-focus',
  imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatPaginatorModule, MatProgressBarModule, MatSelectModule, MatTooltipModule, OpportunityTableComponent],
  template: `
    <div class="page">
      <div class="page-header">
        <div><h1>My focus</h1><p>What you are looking for right now, with matching opportunities and official guidance.</p></div>
        <button mat-stroked-button (click)="startBlank()"><mat-icon>add</mat-icon>New focus</button>
      </div>
      @if (error()) { <div class="error-banner" role="alert">{{ error() }}</div> }

      @if (areas().length === 0 && !editing()) {
        <section class="card start">
          <h2>Start from a template</h2>
          @for (t of templates(); track t.code) {
            <div class="template">
              <div><strong>{{ t.name }}</strong><p class="muted">{{ t.description }}</p></div>
              <button mat-flat-button class="nowrap" style="flex-shrink:0" (click)="startFromTemplate(t)"><mat-icon>bolt</mat-icon>Use template</button>
            </div>
          }
        </section>
      }

      @if (areas().length) {
        <div class="tabs" role="tablist">
          @for (a of areas(); track a.id) {
            <button mat-stroked-button role="tab" [class.active]="a.id === selectedId()" [attr.aria-selected]="a.id === selectedId()"
              (click)="select(a.id)">{{ a.name }}</button>
          }
        </div>
      }

      @if (editing()) {
        <form class="card" [formGroup]="form" (ngSubmit)="save()">
          <h2>{{ editingId() ? 'Edit focus' : 'New focus' }}</h2>
          <div class="fields">
            <mat-form-field appearance="outline"><mat-label>Name</mat-label><input matInput formControlName="name" /></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Provinces (empty = all)</mat-label>
              <mat-select formControlName="provinces" multiple>
                @for (p of provinces; track p[0]) { <mat-option [value]="p[0]">{{ p[1] }}</mat-option> }
              </mat-select></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Opportunity types</mat-label>
              <mat-select formControlName="eventTypes" multiple>
                @for (t of eventTypes; track t) { <mat-option [value]="t">{{ label(t) }}</mat-option> }
              </mat-select></mat-form-field>
          </div>
          <mat-form-field appearance="outline" class="full"><mat-label>Keywords or phrases (comma separated)</mat-label>
            <textarea matInput rows="2" formControlName="keywords"></textarea>
            <mat-hint>Each entry is a word or exact phrase, e.g. "state farm, lease of state land". Title matches are listed first.
              Write acronyms in capitals (IT, ICT) so they are matched exactly.</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline" class="full"><mat-label>Exclude words (comma separated)</mat-label>
            <input matInput formControlName="excludeKeywords" />
            <mat-hint>Hide opportunities whose title or description contains any of these, e.g. "vehicle, hire"</mat-hint>
          </mat-form-field>
          <div class="row">
            <mat-checkbox formControlName="notifyMe">Notify me about new matches</mat-checkbox>
            <mat-checkbox formControlName="includeClosed">Include closed opportunities</mat-checkbox>
            <mat-checkbox formControlName="searchDocuments"
              matTooltip="Broader but noisier: common words appear in most tender packs. These matches are listed last.">
              Also match words found only in tender documents</mat-checkbox>
            <span class="spacer"></span>
            <button mat-button type="button" (click)="cancel()">Cancel</button>
            <button mat-flat-button type="submit" [disabled]="form.invalid || busy()">Save</button>
          </div>
        </form>
      }

      @if (selected(); as f) {
        <div class="layout">
          <section>
            <div class="row" style="margin-bottom:8px">
              <h2 class="section-title">{{ total() }} matching opportunit{{ total() === 1 ? 'y' : 'ies' }}</h2>
              <span class="spacer"></span>
              <button mat-button (click)="edit(f)"><mat-icon>tune</mat-icon>Edit</button>
              <button mat-button (click)="remove(f)"><mat-icon>delete</mat-icon>Delete</button>
            </div>
            @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
            <div class="stack">
              @if (results().length) { <app-opportunity-table [items]="results()" [compact]="true" /> }
              @else {
                @if (!loading()) {
                  <div class="card empty">Nothing open matches this focus right now. You'll be notified when something is published
                    @if (!f.notifyMe) { (turn on notifications to be alerted) }.
                    @if (!f.searchDocuments && f.keywords.length) { You can also <button mat-button (click)="edit(f)">search inside tender documents</button>. }
                  </div>
                }
              }
            </div>
            <mat-paginator [length]="total()" [pageIndex]="page()" [pageSize]="20" [pageSizeOptions]="[]" (page)="onPage($event)" />
          </section>
          <aside class="stack">
            <section class="card">
              <h2>This focus</h2>
              <dl class="kv">
                <dt>Keywords</dt><dd>{{ f.keywords.join(', ') || '—' }}</dd>
                @if (f.excludeKeywords.length) { <dt>Excluding</dt><dd>{{ f.excludeKeywords.join(', ') }}</dd> }
                <dt>Searches</dt><dd>{{ f.searchDocuments ? 'Titles, descriptions and documents' : 'Titles and descriptions' }}</dd>
                <dt>Types</dt><dd>{{ f.eventTypes.map(label).join(', ') || 'Any' }}</dd>
                <dt>Provinces</dt><dd>{{ provinceNames(f.provinces) || 'All' }}</dd>
                <dt>Alerts</dt><dd>{{ f.notifyMe ? 'On' : 'Off' }}</dd>
              </dl>
              @if (f.provinces.length) {
                <p class="muted small">Opportunities that don't state a province are included, because the location is often only in the advert.</p>
              }
            </section>
            @if (f.guidance.length) {
              <section class="card">
                <h2>Official guidance</h2>
                @for (g of f.guidance; track g.url) {
                  <div class="guide">
                    <a [href]="g.url" target="_blank" rel="noopener noreferrer">{{ g.label }} <mat-icon inline>open_in_new</mat-icon></a>
                    @if (g.note) { <div class="muted small">{{ g.note }}</div> }
                  </div>
                }
              </section>
            }
          </aside>
        </div>
      }
    </div>
  `,
  styles: `
    .start .template { display: flex; gap: 16px; align-items: center; justify-content: space-between; padding: 8px 0; }
    .start p { margin: 4px 0 0; }
    .tabs { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
    .tabs .active { background: var(--oip-brand, #0b5c6b); color: #fff; }
    .fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 0 12px; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 16px; align-items: start; }
    .section-title { font: var(--mat-sys-title-medium); margin: 0; }
    .guide { padding: 6px 0; }
    @media (max-width: 900px) { .layout { grid-template-columns: 1fr; } }
  `,
})
export class FocusComponent {
  private readonly api = inject(FocusService);
  protected readonly provinces = PROVINCES;
  protected readonly eventTypes = EVENT_TYPES;

  protected readonly templates = signal<FocusTemplate[]>([]);
  protected readonly areas = signal<FocusArea[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly selected = computed(() => this.areas().find((a) => a.id === this.selectedId()) ?? null);
  protected readonly results = signal<OpportunitySummary[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly editing = signal(false);
  protected readonly editingId = signal<string | null>(null);
  private templateCode: string | null = null;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    keywords: [''],
    excludeKeywords: [''],
    eventTypes: [[] as string[]],
    provinces: [[] as string[]],
    includeClosed: [false],
    searchDocuments: [false],
    notifyMe: [true],
  });

  constructor() {
    this.api.templates().subscribe({ next: (t) => this.templates.set(t), error: (e) => this.fail(e) });
    this.reload();
  }

  protected startFromTemplate(t: FocusTemplate): void {
    this.templateCode = t.code;
    this.editingId.set(null);
    this.form.reset({ name: t.name, keywords: t.keywords.join(', '), excludeKeywords: '', eventTypes: t.eventTypes, provinces: [],
      includeClosed: false, searchDocuments: false, notifyMe: true });
    this.editing.set(true);
  }

  protected startBlank(): void {
    this.templateCode = null;
    this.editingId.set(null);
    this.form.reset({ name: '', keywords: '', excludeKeywords: '', eventTypes: [], provinces: [], includeClosed: false,
      searchDocuments: false, notifyMe: true });
    this.editing.set(true);
  }

  protected edit(f: FocusArea): void {
    this.templateCode = f.templateCode;
    this.editingId.set(f.id);
    this.form.reset({ name: f.name, keywords: f.keywords.join(', '), excludeKeywords: f.excludeKeywords.join(', '),
      eventTypes: f.eventTypes, provinces: f.provinces, includeClosed: f.includeClosed, searchDocuments: f.searchDocuments,
      notifyMe: f.notifyMe });
    this.editing.set(true);
  }

  protected cancel(): void {
    this.editing.set(false);
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const body: FocusRequest = {
      name: v.name.trim(), templateCode: this.templateCode,
      keywords: splitList(v.keywords), excludeKeywords: splitList(v.excludeKeywords),
      eventTypes: v.eventTypes, provinces: v.provinces, sectors: [], includeClosed: v.includeClosed,
      searchDocuments: v.searchDocuments, notifyMe: v.notifyMe,
    };
    if (!body.keywords.length && !body.eventTypes.length) {
      this.error.set('Add at least one keyword or opportunity type.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    const id = this.editingId();
    (id ? this.api.update(id, body) : this.api.create(body)).subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.editing.set(false);
        this.reload(saved.id);
      },
      error: (e) => { this.busy.set(false); this.fail(e); },
    });
  }

  protected remove(f: FocusArea): void {
    if (!confirm(`Delete the focus "${f.name}"?`)) return;
    this.api.delete(f.id).subscribe({ next: () => this.reload(), error: (e) => this.fail(e) });
  }

  protected select(id: string): void {
    this.selectedId.set(id);
    this.page.set(0);
    this.load();
  }

  protected onPage(e: PageEvent): void {
    this.page.set(e.pageIndex);
    this.load();
  }

  protected label(code: string): string {
    if (code === 'LAND_LEASE') return 'State land lease';
    if (['RFQ', 'RFP', 'RFI', 'EOI'].includes(code)) return code;
    const t = code.toLowerCase().replace(/_/g, ' ');
    return t.charAt(0).toUpperCase() + t.slice(1);
  }

  protected provinceNames(codes: string[]): string {
    return codes.map((c) => PROVINCES.find((p) => p[0] === c)?.[1] ?? c).join(', ');
  }

  private reload(selectId?: string): void {
    this.api.list().subscribe({
      next: (areas) => {
        this.areas.set(areas);
        const id = selectId ?? (areas.some((a) => a.id === this.selectedId()) ? this.selectedId() : areas[0]?.id ?? null);
        this.selectedId.set(id);
        if (id) this.load();
        else { this.results.set([]); this.total.set(0); }
      },
      error: (e) => this.fail(e),
    });
  }

  private load(): void {
    const id = this.selectedId();
    if (!id) return;
    this.loading.set(true);
    this.api.opportunities<OpportunitySummary>(id, this.page(), 20).subscribe({
      next: (p) => { this.results.set(p.content); this.total.set(p.totalElements); this.loading.set(false); },
      error: (e) => { this.loading.set(false); this.fail(e); },
    });
  }

  private fail(e: unknown): void {
    const detail = e instanceof HttpErrorResponse ? (e.error?.detail as string | undefined) : undefined;
    this.error.set(detail ?? 'Something went wrong. Please try again.');
  }
}
