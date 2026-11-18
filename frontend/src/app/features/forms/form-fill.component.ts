import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api.service';
import { FormFill, Placement } from '../../core/models';
import { saveBlob } from './bid-forms.component';

/**
 * Review a pre-filled tender form: every proposed value is shown on the page where it will be written. Values can be
 * edited, dragged, removed, or added by clicking on the page; then the filled PDF is generated as a bid document.
 */
@Component({
  selector: 'app-form-fill',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule],
  template: `
    <div class="page">
      <a mat-button [routerLink]="['/opportunities', id()]"><mat-icon>arrow_back</mat-icon>Back to the opportunity</a>
      @if (fill(); as f) {
        <div class="page-header toolbar" style="margin-top:8px">
          <div><h1>{{ f.fileName }}</h1>
            <p>{{ filledCount() }} of {{ placements().length }} values filled ·
              {{ missingCount() }} not in your profile @if (f.ocrPages) { · {{ f.ocrPages }} scanned page(s) read with OCR }</p></div>
          <div class="row">
            <button mat-stroked-button (click)="addMode.set(!addMode())" [class.active]="addMode()">
              <mat-icon>add_comment</mat-icon>{{ addMode() ? 'Click on the page…' : 'Add text' }}</button>
            <button mat-stroked-button (click)="save()" [disabled]="busy() || !dirty()">Save draft</button>
            <button mat-flat-button (click)="generate()" [disabled]="busy()"><mat-icon>picture_as_pdf</mat-icon>Generate filled PDF</button>
          </div>
        </div>
        <div class="warn-banner">Check every value against the original form and your certificates before signing and submitting.
          Values in <strong>blue</strong> are written onto the form; drag a box to move it.</div>
        @if (f.idNumbersWithheld) { <div class="info-banner">Identity numbers are left empty: your role cannot view directors' personal information. A profile editor can generate this form with them.</div> }
        @for (w of f.warnings; track $index) { <div class="info-banner">{{ w }}</div> }
        @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
        @if (error()) { <div class="error-banner" role="alert">{{ error() }}</div> }
        @if (generatedId()) {
          <div class="ok-banner row">The filled PDF was added to the bid documents.<span class="spacer"></span>
            <button mat-flat-button (click)="downloadGenerated()"><mat-icon>download</mat-icon>Download</button></div>
        }

        <div class="layout">
          <div class="pages">
            @for (p of f.pages; track $index; let i = $index) {
              <div class="sheet" [style.aspect-ratio]="p.width + ' / ' + p.height" [class.adding]="addMode()" (click)="pageClick($event, i)">
                @if (images()[i]; as src) { <img [src]="src" [alt]="'Page ' + (i + 1)" draggable="false" /> } @else { <div class="loading">Page {{ i + 1 }}…</div> }
                @for (pl of onPage(i); track pl.id) {
                  <div class="box" [class.missing]="!pl.value" [class.selected]="selected() === pl.id"
                       [style.left.%]="pl.x / p.width * 100" [style.top.%]="pl.y / p.height * 100"
                       [style.width.%]="pl.width / p.width * 100" [style.height.%]="pl.height / p.height * 100"
                       [style.font-size]="(pl.fontSize / p.width * 100) + 'cqw'"
                       [matTooltip]="(pl.fieldLabel ?? 'Text') + (pl.labelText ? ' — ' + pl.labelText : '')"
                       (pointerdown)="dragStart($event, pl, p.width, p.height)" (click)="$event.stopPropagation(); select(pl.id)">
                    {{ pl.value || (pl.fieldLabel ?? '') }}
                  </div>
                }
                <span class="page-no">{{ i + 1 }}</span>
              </div>
            }
          </div>

          <aside class="side card">
            <h2>Values</h2>
            @for (pl of placements(); track pl.id) {
              <div class="entry" [class.selected]="selected() === pl.id" [id]="'entry-' + pl.id" (click)="select(pl.id, true)">
                <div class="entry-head">
                  <div class="entry-title"><strong class="small">{{ pl.fieldLabel ?? 'Text' }}</strong>
                    <span class="xs muted">p{{ pl.page + 1 }} · {{ sourceLabel(pl) }}</span></div>
                  <button mat-icon-button class="small-btn" aria-label="Remove" (click)="$event.stopPropagation(); remove(pl.id)"><mat-icon>close</mat-icon></button>
                </div>
                @if (pl.field === 'DIRECTOR_ID') {
                  <div class="xs muted">{{ pl.value || 'Not available' }} — filled in from your encrypted directors list when the PDF is generated.</div>
                } @else {
                  <input class="inline full" [ngModel]="pl.value" (ngModelChange)="setValue(pl.id, $event)"
                         [placeholder]="pl.missing ? 'Not in your company profile — type it or remove' : ''" [attr.aria-label]="pl.fieldLabel ?? 'Text'" />
                }
                @if (pl.labelText) { <div class="xs muted">{{ pl.labelText }}</div> }
              </div>
            } @empty { <p class="muted small">No values yet. Use “Add text” and click on the page.</p> }
          </aside>
        </div>
      } @else if (error()) { <div class="error-banner">{{ error() }}</div> } @else { <mat-progress-bar mode="indeterminate" /> }
    </div>
  `,
  styles: `
    .toolbar { position: sticky; top: 0; z-index: 5; background: var(--oip-bg); padding: 8px 0; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 16px; align-items: start; margin-top: 12px; }
    @media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } }
    .pages { display: flex; flex-direction: column; gap: 16px; }
    .sheet { position: relative; width: 100%; max-width: 900px; background: #fff; box-shadow: 0 1px 4px rgba(0,0,0,.2); container-type: inline-size; user-select: none; }
    .sheet.adding { cursor: crosshair; }
    .sheet img { position: absolute; inset: 0; width: 100%; height: 100%; }
    .loading { padding: 40px; color: #888; }
    .page-no { position: absolute; right: 6px; bottom: 4px; font-size: 11px; color: #888; }
    .box { position: absolute; color: #0d1a73; background: rgba(66, 133, 244, .12); outline: 1px solid rgba(66, 133, 244, .6);
           font-family: Helvetica, Arial, sans-serif; line-height: 1.1; overflow: hidden; white-space: nowrap; cursor: move; padding-left: 1px; }
    .box.missing { color: #a15c00; background: rgba(255, 170, 0, .15); outline-color: rgba(255, 140, 0, .8); font-style: italic; }
    .box.selected { outline: 2px solid #1a56db; z-index: 2; }
    .side { position: sticky; top: 8px; max-height: calc(100vh - 32px); overflow: auto; }
    .entry { padding: 8px 4px; border-bottom: 1px solid var(--oip-border); cursor: pointer; }
    .entry.selected { background: rgba(66, 133, 244, .08); }
    .entry-head { display: flex; align-items: flex-start; gap: 4px; }
    .entry-title { flex: 1; min-width: 0; display: flex; flex-direction: column; }
    .small-btn { transform: scale(.8); flex: none; margin: -8px -8px 0 0; }
    button.active { background: rgba(66, 133, 244, .12); }
    .ok-banner { background: #e6f4ea; color: #1e4620; padding: 8px 12px; border-radius: 8px; margin: 8px 0; }
  `,
})
export class FormFillComponent {
  private readonly api = inject(ApiService);
  readonly id = input.required<string>();
  readonly fillId = input.required<string>();
  protected readonly fill = signal<FormFill | null>(null);
  protected readonly placements = signal<Placement[]>([]);
  protected readonly images = signal<(string | null)[]>([]);
  protected readonly selected = signal<string | null>(null);
  protected readonly addMode = signal(false);
  protected readonly dirty = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly generatedId = signal<string | null>(null);
  protected readonly filledCount = computed(() => this.placements().filter((p) => !!p.value).length);
  protected readonly missingCount = computed(() => this.placements().filter((p) => !p.value).length);
  private readonly objectUrls: string[] = [];
  private dragMoved = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.objectUrls.forEach((u) => URL.revokeObjectURL(u)));
  }

  ngOnInit(): void {
    this.api.formFill(this.id(), this.fillId()).subscribe({
      next: (f) => { this.apply(f); this.loadImages(f); },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  private apply(f: FormFill): void {
    this.fill.set(f);
    this.placements.set(f.placements);
    this.dirty.set(false);
    if (f.status === 'GENERATED' && f.bidDocumentId) this.generatedId.set(f.bidDocumentId);
  }

  /** Page previews need the access token, so they are fetched as blobs rather than plain image URLs. */
  private loadImages(f: FormFill): void {
    this.images.set(f.pages.map(() => null));
    const requests = f.pages.map((_, i) => this.api.download(this.api.formFillPageUrl(this.id(), this.fillId(), i)));
    forkJoin(requests).subscribe({
      next: (blobs) => {
        const urls = blobs.map((b) => URL.createObjectURL(b));
        this.objectUrls.push(...urls);
        this.images.set(urls);
      },
      error: (e) => this.error.set('Page previews could not be loaded: ' + errorMessage(e)),
    });
  }

  protected onPage(i: number): Placement[] {
    return this.placements().filter((p) => p.page === i);
  }

  protected sourceLabel(p: Placement): string {
    return { LABEL: 'found by label', TABLE: 'directors table', ACROFORM: 'form field', MANUAL: 'added by you' }[p.source]
      + (p.source !== 'MANUAL' && p.source !== 'ACROFORM' && p.confidence < 0.7 ? ' · check position' : '');
  }

  protected select(id: string, fromList = false): void {
    if (this.dragMoved) return;
    this.selected.set(id);
    if (!fromList) document.getElementById('entry-' + id)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  protected setValue(id: string, value: string): void {
    this.update(id, (p) => ({ ...p, value }));
  }

  protected remove(id: string): void {
    this.placements.update((ps) => ps.filter((p) => p.id !== id));
    this.dirty.set(true);
  }

  private update(id: string, fn: (p: Placement) => Placement): void {
    this.placements.update((ps) => ps.map((p) => (p.id === id ? fn(p) : p)));
    this.dirty.set(true);
  }

  /** In "Add text" mode, a click on the page adds a free-text value there. */
  protected pageClick(event: MouseEvent, page: number): void {
    if (!this.addMode()) return;
    const f = this.fill()!;
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    const size = f.pages[page];
    const x = ((event.clientX - rect.left) / rect.width) * size.width;
    const y = ((event.clientY - rect.top) / rect.height) * size.height;
    const p: Placement = {
      id: crypto.randomUUID(), page, x: Math.max(0, x), y: Math.max(0, y - 6), width: Math.min(180, size.width - x), height: 12,
      fontSize: 9, field: 'FREE_TEXT', fieldLabel: 'Text', value: '', source: 'MANUAL', confidence: 1, labelText: null,
      acroField: null, missing: false, ref: null,
    };
    this.placements.update((ps) => [...ps, p]);
    this.dirty.set(true);
    this.addMode.set(false);
    this.selected.set(p.id);
    setTimeout(() => (document.querySelector(`#entry-${p.id} input`) as HTMLInputElement | null)?.focus());
  }

  /** Drag a box to fine-tune where its value is written. */
  protected dragStart(event: PointerEvent, p: Placement, pageWidth: number, pageHeight: number): void {
    if (this.addMode()) return;
    const box = event.currentTarget as HTMLElement;
    const sheet = box.parentElement!.getBoundingClientRect();
    const startX = event.clientX, startY = event.clientY, origX = p.x, origY = p.y;
    this.dragMoved = false;
    box.setPointerCapture(event.pointerId);
    const move = (e: PointerEvent) => {
      const dx = ((e.clientX - startX) / sheet.width) * pageWidth;
      const dy = ((e.clientY - startY) / sheet.height) * pageHeight;
      if (Math.abs(dx) + Math.abs(dy) > 1) this.dragMoved = true;
      this.update(p.id, (q) => ({ ...q, x: clamp(origX + dx, 0, pageWidth - 4), y: clamp(origY + dy, 0, pageHeight - 4) }));
    };
    const up = () => {
      box.removeEventListener('pointermove', move);
      box.removeEventListener('pointerup', up);
      setTimeout(() => (this.dragMoved = false));
    };
    box.addEventListener('pointermove', move);
    box.addEventListener('pointerup', up);
  }

  protected save(after?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.saveFormFill(this.id(), this.fillId(), this.placements()).subscribe({
      next: (f) => { this.busy.set(false); this.apply(f); after?.(); },
      error: (e) => { this.busy.set(false); this.error.set(errorMessage(e)); },
    });
  }

  protected generate(): void {
    const run = () => {
      this.busy.set(true);
      this.api.generateFormFill(this.id(), this.fillId()).subscribe({
        next: (f) => { this.busy.set(false); this.apply(f); this.generatedId.set(f.bidDocumentId); },
        error: (e) => { this.busy.set(false); this.error.set(errorMessage(e)); },
      });
    };
    if (this.dirty()) this.save(run); else run();
  }

  protected downloadGenerated(): void {
    const docId = this.generatedId();
    if (!docId) return;
    this.api.download(this.api.bidDocumentUrl(this.id(), docId)).subscribe({
      next: (blob) => saveBlob(blob, this.fill()!.fileName.replace(/\.pdf$/i, '') + ' (filled).pdf'),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(Math.max(v, min), max);
}
