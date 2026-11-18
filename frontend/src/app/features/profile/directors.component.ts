import { NgTemplateOutlet } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiService, errorMessage } from '../../core/api.service';
import { LabelPipe } from '../../core/format';
import { Director, DirectorRequest, DirectorRole } from '../../core/models';

/**
 * Directors, members, trustees and shareholders: a company has any number of them. Used for the SBD 4 declaration.
 * Identity numbers are personal information: stored encrypted, shown masked, never sent back to the browser.
 * Lives inside the profile page's form, so it uses a [formGroup] div and type="button" buttons, never a nested form.
 */
@Component({
  selector: 'app-directors',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatCheckboxModule, LabelPipe, NgTemplateOutlet],
  template: `
    <section class="card">
      <div class="row"><h2 style="margin:0">Directors / members</h2><span class="spacer"></span>
        @if (editing() === null) { <button mat-button type="button" (click)="startAdd()"><mat-icon>add</mat-icon>Add</button> }</div>
      <p class="muted small" style="margin-top:4px">Used for the declaration of interest (SBD 4). Identity numbers are stored encrypted and
        only ever shown masked; they are written into a form only when you generate it.</p>
      @if (error()) { <div class="error-banner" role="alert">{{ error() }}</div> }

      @for (d of directors(); track d.id) {
        <div class="director">
          <div class="row">
            <strong>{{ d.fullName }}</strong><span class="pill">{{ d.role | label }}</span>
            @if (d.shareholdingPercentage !== null) { <span class="muted small">{{ d.shareholdingPercentage }}%</span> }
            @if (d.inServiceOfState) { <span class="pill warn">In the service of the state</span> }
            <span class="spacer"></span>
            @if (editing() === null) {
              <button mat-icon-button type="button" aria-label="Edit director" (click)="startEdit(d)"><mat-icon>edit</mat-icon></button>
              <button mat-icon-button type="button" aria-label="Remove director" (click)="remove(d)"><mat-icon>delete</mat-icon></button>
            }
          </div>
          <div class="muted small">ID: {{ d.idNumberMasked ?? 'not recorded' }}@if (d.incomeTaxNumber) { · Tax: {{ d.incomeTaxNumber }} }</div>
          @if (editing() === d.id) { <ng-container *ngTemplateOutlet="editor" /> }
        </div>
      } @empty { @if (editing() !== 'new') { <p class="muted">No directors or members added.</p> } }
      @if (editing() === 'new') { <ng-container *ngTemplateOutlet="editor" /> }

      <ng-template #editor>
        <div class="editor" [formGroup]="form" (keydown.enter)="$event.preventDefault()">
          <div class="fields">
            <mat-form-field appearance="outline"><mat-label>Full name</mat-label><input matInput formControlName="fullName" /></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Role</mat-label>
              <mat-select formControlName="role">@for (r of roles; track r) { <mat-option [value]="r">{{ r | label }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>ID type</mat-label>
              <mat-select formControlName="idType"><mat-option value="SA_ID">South African ID</mat-option><mat-option value="PASSPORT">Passport</mat-option></mat-select></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>{{ form.controls.idType.value === 'PASSPORT' ? 'Passport number' : 'Identity number' }}</mat-label>
              <input matInput formControlName="idNumber" autocomplete="off" [placeholder]="editingMasked() ?? ''" />
              <mat-hint>{{ editingMasked() ? 'Leave empty to keep ' + editingMasked() : 'Stored encrypted' }}</mat-hint>
              @if (form.controls.idNumber.hasError('pattern')) { <mat-error>{{ form.controls.idType.value === 'PASSPORT' ? '5–20 letters and digits' : '13 digits' }}</mat-error> }
            </mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Income tax number</mat-label><input matInput formControlName="incomeTaxNumber" />
              @if (form.controls.incomeTaxNumber.hasError('pattern')) { <mat-error>10 digits</mat-error> }</mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Shareholding %</mat-label><input matInput type="number" min="0" max="100" formControlName="shareholdingPercentage" /></mat-form-field>
          </div>
          <mat-checkbox formControlName="inServiceOfState">In the service of the state (SBD 4)</mat-checkbox>
          <div class="row" style="justify-content:flex-end">
            @if (editingMasked()) { <button mat-button type="button" (click)="clearId()">Remove ID number</button> }
            <button mat-button type="button" (click)="cancel()">Cancel</button>
            <button mat-flat-button type="button" (click)="save()" [disabled]="form.invalid || busy()">Save</button>
          </div>
        </div>
      </ng-template>
    </section>
  `,
  styles: `
    .director { padding: 10px 0; border-bottom: 1px solid var(--oip-border); }
    .editor { padding: 12px; margin: 8px 0; border: 1px dashed var(--oip-border-strong); border-radius: 8px; }
    .fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 0 16px; }
  `,
})
export class DirectorsComponent {
  private readonly api = inject(ApiService);
  protected readonly roles: DirectorRole[] = ['DIRECTOR', 'MEMBER', 'SHAREHOLDER', 'TRUSTEE'];
  protected readonly directors = signal<Director[]>([]);
  /** null: not editing; 'new': adding; otherwise the id being edited. */
  protected readonly editing = signal<string | null>(null);
  protected readonly editingMasked = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private removeId = false;

  protected readonly form = inject(FormBuilder).group({
    fullName: ['', [Validators.required, Validators.maxLength(200)]],
    role: ['DIRECTOR' as DirectorRole, Validators.required],
    idType: ['SA_ID' as 'SA_ID' | 'PASSPORT'],
    idNumber: ['', Validators.pattern(/^(\d{13}|[A-Za-z0-9]{5,20})?$/)],
    incomeTaxNumber: ['', Validators.pattern(/^(\d{10})?$/)],
    shareholdingPercentage: [null as number | null, [Validators.min(0), Validators.max(100)]],
    inServiceOfState: [false],
  });

  constructor() {
    this.reload();
  }

  private reload(): void {
    this.api.directors().subscribe({ next: (d) => this.directors.set(d), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected startAdd(): void {
    this.form.reset({ fullName: '', role: 'DIRECTOR', idType: 'SA_ID', idNumber: '', incomeTaxNumber: '', shareholdingPercentage: null, inServiceOfState: false });
    this.editingMasked.set(null);
    this.removeId = false;
    this.editing.set('new');
  }

  protected startEdit(d: Director): void {
    this.form.reset({ fullName: d.fullName, role: d.role, idType: d.idType ?? 'SA_ID', idNumber: '', incomeTaxNumber: d.incomeTaxNumber ?? '',
      shareholdingPercentage: d.shareholdingPercentage, inServiceOfState: d.inServiceOfState });
    this.editingMasked.set(d.idNumberMasked);
    this.removeId = false;
    this.editing.set(d.id);
  }

  protected clearId(): void {
    this.removeId = true;
    this.editingMasked.set(null);
  }

  protected cancel(): void {
    this.editing.set(null);
    this.error.set(null);
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const typed = (v.idNumber ?? '').replace(/\s/g, '');
    const body: DirectorRequest = {
      fullName: (v.fullName ?? '').trim(), role: v.role!, idType: v.idType,
      // Empty field while editing keeps the stored number; "Remove ID number" sends '' to delete it.
      idNumber: typed ? typed : this.removeId ? '' : null,
      incomeTaxNumber: v.incomeTaxNumber ? v.incomeTaxNumber : null,
      shareholdingPercentage: v.shareholdingPercentage, inServiceOfState: !!v.inServiceOfState,
    };
    const id = this.editing();
    this.busy.set(true);
    this.error.set(null);
    (id && id !== 'new' ? this.api.updateDirector(id, body) : this.api.addDirector(body)).subscribe({
      next: () => { this.busy.set(false); this.editing.set(null); this.form.controls.idNumber.reset(''); this.reload(); },
      error: (e) => { this.busy.set(false); this.error.set(errorMessage(e)); },
    });
  }

  protected remove(d: Director): void {
    if (!confirm(`Remove ${d.fullName}?`)) return;
    this.api.deleteDirector(d.id).subscribe({ next: () => this.reload(), error: (e) => this.error.set(errorMessage(e)) });
  }
}
