import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { ActivatedRoute, Router } from '@angular/router';
import { combineLatest, debounceTime, startWith, switchMap } from 'rxjs';
import { ApiService, errorMessage } from '../../core/api.service';
import { LabelPipe } from '../../core/format';
import { OrganisationSummary, Sector } from '../../core/models';
import { AuthService } from '../../core/auth.service';
import { MatButtonModule } from '@angular/material/button';
import { OrganisationFormComponent } from './organisation-editor.component';

@Component({
  selector: 'app-organisation-list',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatIconModule, MatButtonToggleModule, MatPaginatorModule, LabelPipe, MatButtonModule,
    OrganisationFormComponent],
  template: `
    <div class="page">
      <div class="page-header">
        <div><h1>Organisations</h1><p>Procuring entities, mining companies and aggregators</p></div>
        @if (isAdmin() && !creating()) { <button mat-flat-button (click)="creating.set(true)"><mat-icon>add</mat-icon>New organisation</button> }
      </div>
      @if (creating()) {
        <app-organisation-form (saved)="openCreated($event.organisation.id)" (cancelled)="creating.set(false)" />
        <div style="height:12px"></div>
      }
      <div class="row" style="margin-bottom:12px">
        <mat-form-field appearance="outline" style="flex:1 1 280px">
          <mat-label>Search organisations</mat-label>
          <mat-icon matPrefix>search</mat-icon>
          <input matInput [formControl]="q" />
        </mat-form-field>
        <mat-button-toggle-group [formControl]="sector" aria-label="Sector">
          <mat-button-toggle [value]="null">All</mat-button-toggle>
          <mat-button-toggle value="GOVERNMENT">Government</mat-button-toggle>
          <mat-button-toggle value="MINING">Mining</mat-button-toggle>
          <mat-button-toggle value="AGGREGATOR">Aggregators</mat-button-toggle>
        </mat-button-toggle-group>
      </div>
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      <div class="card table-scroll" style="padding:0">
        <table class="data">
          <thead><tr><th>Name</th><th>Type</th><th>Sector</th><th>Website</th></tr></thead>
          <tbody>
            @for (o of items(); track o.id) {
              <tr class="clickable" (click)="open(o.id)" (keydown.enter)="open(o.id)" tabindex="0">
                <td>{{ o.name }}</td>
                <td>{{ o.organisationType | label }}</td>
                <td>{{ o.sector | label }}</td>
                <td>
                  @if (o.website) { <span class="small">{{ o.website }}</span> } @else { <span class="muted small">Not verified</span> }
                  @if (o.websiteVerification === 'BLOCKED') { <span class="pill warn">Blocks automated access</span> }
                </td>
              </tr>
            } @empty { <tr><td colspan="4" class="empty">No organisations found.</td></tr> }
          </tbody>
        </table>
      </div>
      <mat-paginator [length]="total()" [pageIndex]="page()" [pageSize]="50" [pageSizeOptions]="[]" (page)="onPage($event)" />
    </div>
  `,
})
export class OrganisationListComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly isAdmin = inject(AuthService).isPlatformAdmin;
  protected readonly creating = signal(false);

  protected openCreated(id: string): void {
    void this.router.navigate(['/organisations', id]);
  }
  protected readonly q = new FormControl('', { nonNullable: true });
  protected readonly sector = new FormControl<Sector | null>(
    (inject(ActivatedRoute).snapshot.queryParamMap.get('sector') as Sector | null) ?? null);
  protected readonly items = signal<OrganisationSummary[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly error = signal<string | null>(null);

  constructor() {
    combineLatest([this.q.valueChanges.pipe(startWith(''), debounceTime(250)), this.sector.valueChanges.pipe(startWith(this.sector.value))])
      .pipe(
        switchMap(([q, sector]) => {
          this.page.set(0);
          return this.api.organisations({ q, sector: sector ?? undefined, page: 0, size: 50 });
        }),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe({
        next: (p) => { this.items.set(p.content); this.total.set(p.totalElements); },
        error: (e) => this.error.set(errorMessage(e)),
      });
  }

  protected onPage(e: PageEvent): void {
    this.page.set(e.pageIndex);
    this.api.organisations({ q: this.q.value, sector: this.sector.value ?? undefined, page: e.pageIndex, size: 50 })
      .subscribe((p) => this.items.set(p.content));
  }

  protected open(id: string): void {
    void this.router.navigate(['/organisations', id]);
  }
}
