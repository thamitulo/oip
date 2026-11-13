import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService, errorMessage } from '../../core/api.service';
import { LabelPipe } from '../../core/format';
import { ChannelRequest, OrganisationDetail, OrganisationRequest, PROVINCES, SupplierPlatformSummary } from '../../core/models';

export const ORGANISATION_TYPES = ['NATIONAL_DEPARTMENT', 'PROVINCIAL_DEPARTMENT', 'MUNICIPALITY', 'MUNICIPAL_ENTITY', 'PUBLIC_ENTITY',
  'STATE_OWNED_COMPANY', 'EDUCATIONAL_INSTITUTION', 'WATER_BOARD', 'ENERGY_ENTITY', 'TRANSPORT_ENTITY', 'HEALTHCARE_INSTITUTION',
  'MINING_COMPANY', 'PRIVATE_COMPANY', 'AGGREGATOR', 'OTHER'];
const SECTORS = ['GOVERNMENT', 'MINING', 'PRIVATE', 'AGGREGATOR'];
const WEBSITE_VERIFICATION = ['UNVERIFIED', 'VERIFIED', 'UNREACHABLE', 'BLOCKED'];
const CHANNEL_TYPES = ['PUBLIC_TENDER_LISTING', 'EOI_ESD_PAGE', 'SUPPLIER_REGISTRATION', 'SUPPLIER_PLATFORM', 'LOGIN_RFQ_SYSTEM',
  'PROCUREMENT_CONTACT', 'PUBLIC_API', 'NO_PUBLIC_FEED_FOUND'];
const CHANNEL_VERIFICATION = ['PENDING_VERIFICATION', 'VERIFIED', 'UNREACHABLE', 'BLOCKED'];
const URL_PATTERN = /^(https?:\/\/.+)?$/;

/** Organisation details form (create or edit). Platform administrators only; the API enforces this too. */
@Component({
  selector: 'app-organisation-form',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, LabelPipe],
  template: `
    <form class="card" [formGroup]="form" (ngSubmit)="save()">
      <h2>{{ organisation() ? 'Edit organisation' : 'New organisation' }}</h2>
      <div class="form-grid">
        <mat-form-field appearance="outline" class="wide"><mat-label>Name</mat-label><input matInput formControlName="name" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Short name</mat-label><input matInput formControlName="shortName" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Type</mat-label>
          <mat-select formControlName="organisationType">@for (t of types; track t) { <mat-option [value]="t">{{ t | label }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Sector</mat-label>
          <mat-select formControlName="sector">@for (t of sectors; track t) { <mat-option [value]="t">{{ t | label }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Province</mat-label>
          <mat-select formControlName="province"><mat-option [value]="null">National / not specific</mat-option>
            @for (p of provinces; track p.code) { <mat-option [value]="p.code">{{ p.label }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field appearance="outline" class="wide"><mat-label>Official website</mat-label><input matInput formControlName="website" placeholder="https://" />
          @if (form.controls.website.hasError('pattern')) { <mat-error>Must start with http:// or https://</mat-error> }</mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Website verified?</mat-label>
          <mat-select formControlName="websiteVerification">@for (t of websiteStates; track t) { <mat-option [value]="t">{{ t | label }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field appearance="outline" class="wide"><mat-label>Primary commodities (mining)</mat-label><input matInput formControlName="primaryCommodities" /></mat-form-field>
        <mat-form-field appearance="outline" class="full-row"><mat-label>Notes</mat-label><textarea matInput rows="3" formControlName="notes"></textarea>
          <mat-hint>Record how this was verified (source, date). Do not record guesses as facts.</mat-hint></mat-form-field>
      </div>
      @if (error()) { <div class="error-banner" style="margin-top:8px">{{ error() }}</div> }
      <div class="row" style="justify-content:flex-end;margin-top:12px">
        <button mat-button type="button" (click)="cancelled.emit()">Cancel</button>
        <button mat-flat-button type="submit" [disabled]="form.invalid || busy()">Save</button>
      </div>
    </form>
  `,
  styles: `
    .form-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 4px 10px; }
    .form-grid mat-form-field { width: 100%; }
    .wide { grid-column: span 2; }
    .full-row { grid-column: 1 / -1; }
    @media (max-width: 900px) { .form-grid { grid-template-columns: 1fr 1fr; } }
  `,
})
export class OrganisationFormComponent {
  private readonly api = inject(ApiService);
  readonly organisation = input<OrganisationDetail | null>(null);
  readonly saved = output<OrganisationDetail>();
  readonly cancelled = output<void>();
  protected readonly types = ORGANISATION_TYPES;
  protected readonly sectors = SECTORS;
  protected readonly websiteStates = WEBSITE_VERIFICATION;
  protected readonly provinces = PROVINCES;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(300)]],
    shortName: [null as string | null, Validators.maxLength(100)],
    organisationType: ['OTHER', Validators.required],
    sector: ['GOVERNMENT', Validators.required],
    province: [null as string | null],
    website: [null as string | null, Validators.pattern(URL_PATTERN)],
    websiteVerification: ['UNVERIFIED', Validators.required],
    primaryCommodities: [null as string | null, Validators.maxLength(500)],
    notes: [null as string | null, Validators.maxLength(5000)],
  });

  constructor() {
    effect(() => {
      const d = this.organisation();
      if (d) {
        const o = d.organisation;
        this.form.reset({ name: o.name, shortName: o.shortName, organisationType: o.organisationType, sector: o.sector,
          province: o.province, website: o.website, websiteVerification: o.websiteVerification,
          primaryCommodities: d.primaryCommodities, notes: d.notes });
      }
    });
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const body: OrganisationRequest = {
      name: (v.name ?? '').trim(), shortName: blank(v.shortName), organisationType: v.organisationType!, sector: v.sector as OrganisationRequest['sector'],
      website: blank(v.website), websiteVerification: v.websiteVerification!, province: v.province,
      notes: blank(v.notes), primaryCommodities: blank(v.primaryCommodities),
    };
    const id = this.organisation()?.organisation.id;
    this.busy.set(true);
    this.error.set(null);
    (id ? this.api.updateOrganisation(id, body) : this.api.createOrganisation(body)).subscribe({
      next: (d) => { this.busy.set(false); this.saved.emit(d); },
      error: (e) => { this.busy.set(false); this.error.set(errorMessage(e)); },
    });
  }
}

/** Procurement channels of one organisation: add, edit and remove (platform administrators). */
@Component({
  selector: 'app-channel-editor',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, LabelPipe],
  template: `
    <form class="channel-form" [formGroup]="form" (ngSubmit)="save()">
      <div class="form-grid">
        <mat-form-field appearance="outline" class="wide"><mat-label>Channel name</mat-label><input matInput formControlName="name" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Type</mat-label>
          <mat-select formControlName="channelType">@for (t of channelTypes; track t) { <mat-option [value]="t">{{ t | label }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Verification</mat-label>
          <mat-select formControlName="verificationStatus">@for (t of verification; track t) { <mat-option [value]="t">{{ t | label }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field appearance="outline" class="wide"><mat-label>Official URL</mat-label><input matInput formControlName="url" placeholder="https://" />
          @if (form.controls.url.hasError('pattern')) { <mat-error>Must start with http:// or https://</mat-error> }</mat-form-field>
        <mat-form-field appearance="outline" class="wide"><mat-label>Supplier platform</mat-label>
          <mat-select formControlName="supplierPlatformId"><mat-option [value]="null">None</mat-option>
            @for (p of platforms(); track p.id) { <mat-option [value]="p.id">{{ p.name }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field appearance="outline" class="full-row"><mat-label>Description</mat-label><textarea matInput rows="2" formControlName="description"></textarea></mat-form-field>
      </div>
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      <div class="row" style="justify-content:flex-end">
        <button mat-button type="button" (click)="cancelled.emit()">Cancel</button>
        <button mat-flat-button type="submit" [disabled]="form.invalid || busy()">{{ channel() ? 'Save channel' : 'Add channel' }}</button>
      </div>
    </form>
  `,
  styles: `
    .channel-form { padding: 12px; margin: 8px 0; border: 1px dashed var(--oip-border-strong); border-radius: 8px; background: #fcfcfd; }
    .form-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 4px 10px; }
    .form-grid mat-form-field { width: 100%; }
    .wide { grid-column: span 2; }
    .full-row { grid-column: 1 / -1; }
    @media (max-width: 900px) { .form-grid { grid-template-columns: 1fr 1fr; } }
  `,
})
export class ChannelEditorComponent {
  private readonly api = inject(ApiService);
  readonly organisationId = input.required<string>();
  readonly channel = input<OrganisationDetail['channels'][number] | null>(null);
  readonly saved = output<OrganisationDetail>();
  readonly cancelled = output<void>();
  protected readonly channelTypes = CHANNEL_TYPES;
  protected readonly verification = CHANNEL_VERIFICATION;
  protected readonly platforms = signal<SupplierPlatformSummary[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    channelType: ['PUBLIC_TENDER_LISTING', Validators.required],
    url: [null as string | null, Validators.pattern(URL_PATTERN)],
    supplierPlatformId: [null as string | null],
    description: [null as string | null, Validators.maxLength(5000)],
    verificationStatus: ['PENDING_VERIFICATION', Validators.required],
  });

  constructor() {
    this.api.supplierPlatforms().subscribe({ next: (p) => this.platforms.set(p) });
    effect(() => {
      const c = this.channel();
      if (c) {
        this.form.reset({ name: c.name, channelType: c.channelType, url: c.url, supplierPlatformId: c.supplierPlatformId,
          description: c.description, verificationStatus: c.verificationStatus });
      }
    });
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const body: ChannelRequest = {
      name: (v.name ?? '').trim(), channelType: v.channelType!, url: blank(v.url), supplierPlatformId: v.supplierPlatformId,
      description: blank(v.description), verificationStatus: v.verificationStatus!,
    };
    const c = this.channel();
    this.busy.set(true);
    this.error.set(null);
    (c ? this.api.updateChannel(this.organisationId(), c.id, body) : this.api.addChannel(this.organisationId(), body)).subscribe({
      next: (d) => { this.busy.set(false); this.saved.emit(d); },
      error: (e) => { this.busy.set(false); this.error.set(errorMessage(e)); },
    });
  }
}

function blank(v: string | null | undefined): string | null {
  const t = (v ?? '').trim();
  return t ? t : null;
}
