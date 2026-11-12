import { Component, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ActivatedRoute } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { LabelPipe } from '../../core/format';
import { CompanyProfile, PROVINCES } from '../../core/models';
import { ChipListInputComponent } from '../../shared/chip-list-input.component';
import { CompanyDocumentsComponent } from './company-documents.component';

@Component({
  selector: 'app-company-profile',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatCheckboxModule, MatProgressBarModule, MatSnackBarModule, ChipListInputComponent, LabelPipe, CompanyDocumentsComponent],
  templateUrl: './company-profile.component.html',
  styles: `
    .fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 0 16px; }
    .sub { border: 1px solid var(--oip-border); border-radius: 8px; padding: 12px 12px 0; margin-bottom: 10px; }
    .actions { position: sticky; bottom: 0; background: var(--oip-bg); padding: 12px 0; display: flex; gap: 8px; justify-content: flex-end; }
  `,
})
export class CompanyProfileComponent {
  private readonly api = inject(ApiService);
  private readonly fb = inject(FormBuilder);
  private readonly snack = inject(MatSnackBar);
  protected readonly canEdit = inject(AuthService).hasPermission('COMPANY_PROFILE_EDIT');
  protected readonly welcome = inject(ActivatedRoute).snapshot.queryParamMap.has('welcome');
  protected readonly provinces = PROVINCES;
  protected readonly regionOptions = [{ code: 'NATIONAL', label: 'National (all provinces)' }, ...PROVINCES];
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  // Simple lists edited as chips
  protected readonly industries = signal<string[]>([]);
  protected readonly services = signal<string[]>([]);
  protected readonly products = signal<string[]>([]);
  protected readonly commodities = signal<string[]>([]);

  protected readonly form = this.fb.group({
    legalName: ['', [Validators.required, Validators.maxLength(300)]],
    tradingName: [''], registrationNumber: [''], vatNumber: [''],
    website: ['', Validators.pattern(/^(https?:\/\/.+)?$/)],
    description: [''], province: [null as string | null], city: [''],
    businessSize: [null as string | null], annualTurnoverBand: [''], bbbeeLevel: [null as string | null],
    blackOwnershipPercentage: [null as number | null, [Validators.min(0), Validators.max(100)]],
    enterpriseClassification: [null as string | null], cidbGrading: [''],
    miningSectorExperience: [''], governmentSectorExperience: [''],
    contactName: [''], contactEmail: ['', Validators.email], contactPhone: [''],
    operatingRegions: [[] as string[]],
    certifications: this.fb.array<FormGroup>([]),
    registrations: this.fb.array<FormGroup>([]),
    capabilities: this.fb.array<FormGroup>([]),
    references: this.fb.array<FormGroup>([]),
  });

  protected get certifications(): FormArray<FormGroup> { return this.form.controls.certifications; }
  protected get registrations(): FormArray<FormGroup> { return this.form.controls.registrations; }
  protected get capabilities(): FormArray<FormGroup> { return this.form.controls.capabilities; }
  protected get references(): FormArray<FormGroup> { return this.form.controls.references; }

  constructor() {
    this.api.profile().subscribe({
      next: (p) => { this.load(p); this.loading.set(false); },
      error: (e) => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
  }

  protected addCertification(v: Partial<CompanyProfile['certifications'][number]> = {}): void {
    this.certifications.push(this.fb.group({
      name: [v.name ?? '', Validators.required], issuingBody: [v.issuingBody ?? ''], certificateNumber: [v.certificateNumber ?? ''],
      validUntil: [v.validUntil ?? null], verificationStatus: [v.verificationStatus ?? 'SELF_DECLARED'],
    }));
  }
  protected addRegistration(v: Partial<CompanyProfile['registrations'][number]> = {}): void {
    this.registrations.push(this.fb.group({
      registrationType: [v.registrationType ?? 'CSD', Validators.required], registrationBody: [v.registrationBody ?? '', Validators.required],
      registrationNumber: [v.registrationNumber ?? ''], validUntil: [v.validUntil ?? null], verificationStatus: [v.verificationStatus ?? 'SELF_DECLARED'],
    }));
  }
  protected addCapability(v: Partial<CompanyProfile['capabilities'][number]> = {}): void {
    this.capabilities.push(this.fb.group({
      name: [v.name ?? '', Validators.required], description: [v.description ?? ''],
      yearsExperience: [v.yearsExperience ?? null, [Validators.min(0), Validators.max(100)]],
    }));
  }
  protected addReference(v: Partial<CompanyProfile['references'][number]> = {}): void {
    this.references.push(this.fb.group({
      clientName: [v.clientName ?? '', Validators.required], projectTitle: [v.projectTitle ?? '', Validators.required],
      description: [v.description ?? ''], sector: [v.sector ?? null], contractValue: [v.contractValue ?? null, Validators.min(0)],
      yearCompleted: [v.yearCompleted ?? null, [Validators.min(1950), Validators.max(2100)]],
    }));
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set('Please correct the highlighted fields.');
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    const v = this.form.getRawValue();
    const body = {
      ...emptyToNull(v),
      industries: this.industries(), services: this.services(), products: this.products(), commodities: this.commodities(),
      operatingRegions: v.operatingRegions ?? [],
      certifications: v.certifications.map(emptyToNull), registrations: v.registrations.map(emptyToNull),
      capabilities: v.capabilities.map(emptyToNull), references: v.references.map(emptyToNull),
    } as unknown as CompanyProfile;
    this.api.saveProfile(body).subscribe({
      next: (p) => {
        this.load(p);
        this.saving.set(false);
        this.snack.open('Company profile saved', 'OK', { duration: 3000 });
      },
      error: (e) => { this.error.set(errorMessage(e)); this.saving.set(false); },
    });
  }

  private load(p: CompanyProfile): void {
    this.form.patchValue({ ...p, operatingRegions: p.operatingRegions } as never);
    this.industries.set(p.industries); this.services.set(p.services); this.products.set(p.products); this.commodities.set(p.commodities);
    for (const arr of [this.certifications, this.registrations, this.capabilities, this.references]) arr.clear();
    p.certifications.forEach((c) => this.addCertification(c));
    p.registrations.forEach((r) => this.addRegistration(r));
    p.capabilities.forEach((c) => this.addCapability(c));
    p.references.forEach((r) => this.addReference(r));
    if (!this.canEdit) this.form.disable();
    this.form.markAsPristine();
  }
}

function emptyToNull<T extends object>(o: T): T {
  return Object.fromEntries(Object.entries(o).map(([k, v]) => [k, v === '' ? null : v])) as T;
}
