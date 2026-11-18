import { Component, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { DateTimePipe } from '../../core/format';
import { BidDocument, Fillable } from '../../core/models';

/** Tender forms to pre-fill from the company profile, and the documents prepared for this bid. */
@Component({
  selector: 'app-bid-forms',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, DateTimePipe],
  template: `
    <section class="card">
      <h2>Tender forms</h2>
      <p class="muted small" style="margin-top:0">Pre-fill the tender's own forms (SBD 1, SBD 4, …) with your company details, review them, then download.</p>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /><div class="xs muted">{{ busy() }}</div> }
      @if (error()) { <div class="error-banner" role="alert">{{ error() }}</div> }
      @if (fillable(); as f) {
        @for (d of f.documents; track d.opportunityDocumentId + (d.zipEntry ?? '')) {
          <div class="row item">
            <mat-icon class="muted">picture_as_pdf</mat-icon><span class="small grow">{{ d.title }}</span>
            @if (canEdit()) { <button mat-stroked-button (click)="start(d.opportunityDocumentId, d.zipEntry)" [disabled]="!!busy()">Fill</button> }
          </div>
        } @empty { <div class="xs muted">No PDF documents from the source. Upload the form instead.</div> }
        @if (canEdit()) {
          <label class="upload"><mat-icon>upload_file</mat-icon> Upload a PDF form to fill
            <input type="file" accept="application/pdf,.pdf" (change)="upload($event)" hidden /></label>
        }
        @if (!f.ocrAvailable) { <div class="xs muted">Scanned forms cannot be read on this server (OCR unavailable); digital PDFs work.</div> }
        @if (f.fills.length) {
          <div class="section-title">Your filled forms</div>
          @for (x of f.fills; track x.id) {
            <div class="row item">
              <span class="small grow">{{ x.fileName }}</span>
              <span class="pill" [class.ok]="x.status === 'GENERATED'">{{ x.status === 'GENERATED' ? 'Generated' : 'Draft' }}</span>
              @if (canEdit()) { <button mat-button (click)="open(x.id)">Review</button> }
            </div>
          }
        }
      }

      <div class="section-title">Bid documents</div>
      @for (d of documents(); track d.id) {
        <div class="row item">
          <span class="small grow">{{ d.fileName }}<span class="xs muted"> · {{ d.uploadedAt | dateTime }}</span></span>
          <button mat-icon-button (click)="download(d)" aria-label="Download"><mat-icon>download</mat-icon></button>
          @if (canEdit()) { <button mat-icon-button (click)="remove(d)" aria-label="Delete"><mat-icon>delete</mat-icon></button> }
        </div>
      } @empty { <div class="xs muted">No bid documents yet.</div> }
      @if (canEdit()) {
        <label class="upload"><mat-icon>attach_file</mat-icon> Add a bid document
          <input type="file" (change)="uploadBidDocument($event)" hidden /></label>
      }
    </section>
  `,
  styles: `
    .item { padding: 4px 0; border-bottom: 1px solid var(--oip-border); }
    .grow { flex: 1; min-width: 0; overflow-wrap: anywhere; }
    .upload { display: inline-flex; align-items: center; gap: 6px; margin-top: 8px; cursor: pointer; color: var(--mat-sys-primary); font-size: 13px; }
    .section-title { margin-top: 14px; }
  `,
})
export class BidFormsComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly opportunityId = input.required<string>();
  readonly canEdit = input(false);
  protected readonly fillable = signal<Fillable | null>(null);
  protected readonly documents = signal<BidDocument[]>([]);
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.reload();
  }

  private reload(): void {
    if (this.canEdit()) this.api.fillable(this.opportunityId()).subscribe({ next: (f) => this.fillable.set(f), error: (e) => this.error.set(errorMessage(e)) });
    this.api.bidDocuments(this.opportunityId()).subscribe({ next: (d) => this.documents.set(d), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected start(documentId: string, zipEntry: string | null): void {
    this.busy.set('Reading the form and finding where your details go… scanned pages can take a minute.');
    this.error.set(null);
    this.api.startFormFill(this.opportunityId(), documentId, zipEntry).subscribe({
      next: (f) => { this.busy.set(null); this.open(f.id); },
      error: (e) => { this.busy.set(null); this.error.set(errorMessage(e)); },
    });
  }

  protected upload(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.busy.set('Reading the form…');
    this.error.set(null);
    this.api.uploadFormFill(this.opportunityId(), file).subscribe({
      next: (f) => { this.busy.set(null); this.open(f.id); },
      error: (e) => { this.busy.set(null); this.error.set(errorMessage(e)); },
    });
  }

  protected open(fillId: string): void {
    this.router.navigate(['/opportunities', this.opportunityId(), 'forms', fillId]);
  }

  protected uploadBidDocument(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.api.uploadBidDocument(this.opportunityId(), file).subscribe({ next: () => this.reload(), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected download(d: BidDocument): void {
    this.api.download(this.api.bidDocumentUrl(this.opportunityId(), d.id)).subscribe({
      next: (blob) => saveBlob(blob, d.fileName),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected remove(d: BidDocument): void {
    if (!confirm(`Delete ${d.fileName}?`)) return;
    this.api.deleteBidDocument(this.opportunityId(), d.id).subscribe({ next: () => this.reload(), error: (e) => this.error.set(errorMessage(e)) });
  }
}

export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
}
