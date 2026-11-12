import { Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Router, RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { DateTimePipe, LabelPipe } from '../../core/format';
import { CrawlRun, DryRunResult, OrganisationSummary, Source } from '../../core/models';

const OPTIONS = {
  sourceType: ['TENDER_LISTING', 'ESD_PORTAL', 'SUPPLIER_PORTAL', 'PROCUREMENT_PAGE', 'API', 'RSS_FEED', 'DOCUMENT_LISTING', 'AGGREGATOR', 'COMPANY_WEBSITE'],
  sector: ['GOVERNMENT', 'MINING', 'PRIVATE', 'AGGREGATOR'],
  accessMethod: ['PUBLIC_HTML', 'PUBLIC_API', 'RSS', 'JS_RENDERED', 'DOCUMENT_DOWNLOAD', 'CSV_EXCEL', 'LOGIN_REQUIRED', 'REGISTRATION_REQUIRED', 'LICENSED_FEED', 'MANUAL', 'UNKNOWN'],
  integrationStatus: ['ACTIVE_PUBLIC', 'ACTIVE_API', 'AUTHORISED_INTEGRATION', 'LOGIN_REQUIRED', 'REGISTRATION_REQUIRED', 'PENDING_VERIFICATION', 'TEMPORARILY_UNAVAILABLE', 'DISABLED', 'UNSUPPORTED'],
  robotsPolicy: ['UNKNOWN', 'ALLOWED', 'DISALLOWED', 'NOT_PRESENT'],
  termsReviewStatus: ['NOT_REVIEWED', 'APPROVED', 'RESTRICTED', 'PROHIBITED'],
  reliability: ['UNKNOWN', 'LOW', 'MEDIUM', 'HIGH'],
  adapterType: ['ETENDERS_OCDS', 'HTML_LISTING', 'JSON_API', 'RSS_FEED', 'CSV_LISTING', 'BROWSER_RENDERED', 'AUTHORISED_PLATFORM'],
};

@Component({
  selector: 'app-source-edit',
  imports: [ReactiveFormsModule, RouterLink, MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule, MatButtonModule,
    MatIconModule, MatProgressBarModule, MatSnackBarModule, LabelPipe, DateTimePipe],
  templateUrl: './source-edit.component.html',
  styles: `
    .fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 0 16px; }
    textarea.mono { font-size: 12px; }
    .log { max-height: 220px; overflow: auto; background: #0f2a33; color: #d6e6ea; padding: 10px; border-radius: 8px; white-space: pre-wrap; }
  `,
})
export class SourceEditComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly snack = inject(MatSnackBar);
  readonly id = input<string>();
  protected readonly o = OPTIONS;
  protected readonly source = signal<Source | null>(null);
  protected readonly organisations = signal<OrganisationSummary[]>([]);
  protected readonly runs = signal<CrawlRun[]>([]);
  protected readonly dryRun = signal<DryRunResult | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(300)]],
    organisationId: [null as string | null],
    sourceUrl: ['', Validators.pattern(/^(https?:\/\/.+)?$/)],
    sourceType: ['TENDER_LISTING', Validators.required],
    sector: ['MINING', Validators.required],
    industry: [''],
    accessMethod: ['UNKNOWN', Validators.required],
    integrationStatus: ['PENDING_VERIFICATION', Validators.required],
    enabled: [false],
    authenticationRequired: [null as boolean | null],
    publiclyAccessible: [null as boolean | null],
    robotsPolicy: ['UNKNOWN', Validators.required],
    termsReviewStatus: ['NOT_REVIEWED', Validators.required],
    licenceNotes: [''],
    rateLimitPerMinute: [6, [Validators.required, Validators.min(1), Validators.max(120)]],
    adapterType: [null as string | null],
    parserConfig: [''],
    reliability: ['UNKNOWN', Validators.required],
    priority: [100, [Validators.required, Validators.min(1), Validators.max(1000)]],
    verificationNotes: [''],
    crawlIntervalMinutes: [1440, [Validators.min(15), Validators.max(10080)]],
    markVerified: [false],
  });

  constructor() {
    this.api.organisations({ size: 200 }).subscribe((p) => this.organisations.set(p.content));
    queueMicrotask(() => {
      const id = this.id();
      if (id) this.load(id);
    });
  }

  protected save(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    this.busy.set(true);
    this.error.set(null);
    const body = this.form.getRawValue();
    const id = this.source()?.id;
    (id ? this.api.updateSource(id, body) : this.api.createSource(body)).subscribe({
      next: (s) => {
        this.busy.set(false);
        this.snack.open('Source saved', 'OK', { duration: 3000 });
        if (!id) void this.router.navigate(['/admin/sources', s.id]);
        else this.patch(s);
      },
      error: (e) => { this.error.set(errorMessage(e)); this.busy.set(false); },
    });
  }

  protected test(): void {
    const id = this.source()?.id;
    if (!id) return;
    this.busy.set(true);
    this.dryRun.set(null);
    this.error.set(null);
    this.api.testSource(id).subscribe({
      next: (r) => { this.dryRun.set(r); this.busy.set(false); },
      error: (e) => { this.error.set(errorMessage(e)); this.busy.set(false); },
    });
  }

  protected crawl(): void {
    const id = this.source()?.id;
    if (!id) return;
    this.api.crawlSource(id).subscribe({
      next: () => {
        this.snack.open('Crawl queued. Refresh the history below in a moment.', 'OK', { duration: 4000 });
        setTimeout(() => this.loadRuns(id), 3000);
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected loadRuns(id: string): void {
    this.api.crawlRuns({ sourceId: id, size: 10 }).subscribe((p) => this.runs.set(p.content));
  }

  private load(id: string): void {
    this.api.source(id).subscribe({ next: (s) => this.patch(s), error: (e) => this.error.set(errorMessage(e)) });
    this.loadRuns(id);
  }

  private patch(s: Source): void {
    this.source.set(s);
    this.form.patchValue({ ...s, crawlIntervalMinutes: s.crawlIntervalMinutes ?? 1440, markVerified: false, parserConfig: pretty(s.parserConfig) } as never);
  }
}

function pretty(json: string | null): string {
  if (!json) return '';
  try { return JSON.stringify(JSON.parse(json), null, 2); } catch { return json; }
}
