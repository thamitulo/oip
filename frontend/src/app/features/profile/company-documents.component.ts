import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DateTimePipe, LabelPipe } from '../../core/format';
import { CompanyDocument } from '../../core/models';

const TYPES = ['CAPABILITY_STATEMENT', 'BBBEE_CERTIFICATE', 'CIDB_CERTIFICATE', 'CSD_REPORT', 'TAX_CLEARANCE', 'CERTIFICATION',
  'COMPANY_REGISTRATION', 'REFERENCE_LETTER', 'OTHER'];

/** Upload and manage company documents (capability statements, certificates). */
@Component({
  selector: 'app-company-documents',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, DateTimePipe, LabelPipe],
  template: `
    <section class="card">
      <h2>Company documents</h2>
      <p class="small muted" style="margin-top:0">Capability statements, B-BBEE/CIDB certificates, CSD reports. PDF, Word, Excel, CSV or
        text up to 25 MB. Uploading a document does not mark a claim as verified.</p>
      @if (error()) { <div class="error-banner" style="margin-bottom:8px">{{ error() }}</div> }
      @for (d of docs(); track d.id) {
        <div class="doc">
          <mat-icon>description</mat-icon>
          <div class="grow">
            <div class="strong small">{{ d.fileName }}</div>
            <div class="xs muted">{{ d.documentType | label }} · {{ size(d.sizeBytes) }} · {{ d.uploadedAt | dateTime }}
              · text {{ d.extractionStatus | label }}</div>
          </div>
          <button mat-icon-button (click)="download(d)" aria-label="Download"><mat-icon>download</mat-icon></button>
          @if (canEdit) { <button mat-icon-button (click)="remove(d)" aria-label="Delete"><mat-icon>delete</mat-icon></button> }
        </div>
      } @empty { <div class="xs muted">No documents uploaded.</div> }
      @if (canEdit) {
        <div class="row upload">
          <mat-form-field appearance="outline"><mat-label>Type</mat-label>
            <mat-select [(ngModel)]="type">@for (t of types; track t) { <mat-option [value]="t">{{ t | label }}</mat-option> }</mat-select></mat-form-field>
          <input #file type="file" (change)="chosen = $any($event.target).files?.[0] ?? null"
            accept=".pdf,.doc,.docx,.xls,.xlsx,.csv,.txt" aria-label="Choose file" />
          <button mat-flat-button (click)="upload(file)" [disabled]="!chosen || busy()">{{ busy() ? 'Uploading…' : 'Upload' }}</button>
        </div>
      }
    </section>
  `,
  styles: `
    .doc { display: flex; gap: 10px; align-items: center; padding: 6px 0; border-bottom: 1px solid var(--oip-border); }
    .doc mat-icon { color: var(--oip-muted); }
    .grow { flex: 1; min-width: 0; }
    .upload { margin-top: 10px; }
  `,
})
export class CompanyDocumentsComponent {
  private readonly api = inject(ApiService);
  protected readonly canEdit = inject(AuthService).hasPermission('COMPANY_PROFILE_EDIT');
  protected readonly types = TYPES;
  protected readonly docs = signal<CompanyDocument[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected type = 'CAPABILITY_STATEMENT';
  protected chosen: File | null = null;

  constructor() {
    this.reload();
  }

  protected upload(input: HTMLInputElement): void {
    if (!this.chosen) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.uploadCompanyDocument(this.chosen, this.type, '').subscribe({
      next: () => { this.chosen = null; input.value = ''; this.busy.set(false); this.reload(); },
      error: (e) => { this.error.set(errorMessage(e)); this.busy.set(false); },
    });
  }

  protected download(d: CompanyDocument): void {
    this.api.download(`/api/v1/companies/documents/${d.id}`).subscribe((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = d.fileName;
      a.click();
      URL.revokeObjectURL(url);
    });
  }

  protected remove(d: CompanyDocument): void {
    if (!confirm(`Delete ${d.fileName}?`)) return;
    this.api.deleteCompanyDocument(d.id).subscribe({ next: () => this.reload(), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected size(bytes: number): string {
    return bytes > 1048576 ? (bytes / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB';
  }

  private reload(): void {
    this.api.companyDocuments().subscribe({ next: (d) => this.docs.set(d), error: (e) => this.error.set(errorMessage(e)) });
  }
}
