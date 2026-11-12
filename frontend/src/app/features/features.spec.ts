import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { RegisterComponent } from './auth/register.component';
import { OpportunityListComponent } from './opportunities/opportunity-list.component';

describe('feature components', () => {
  let http: HttpTestingController;

  function setup(routeData: Record<string, unknown> = {}, queryParams: Record<string, string> = {}) {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const route = TestBed.inject(ActivatedRoute);
    Object.assign(route.snapshot, { data: routeData });
    (route.snapshot as unknown as { queryParams: Record<string, string> }).queryParams = queryParams;
    http = TestBed.inject(HttpTestingController);
  }

  it('register form requires valid fields and accepted terms', async () => {
    setup();
    const fixture = TestBed.createComponent(RegisterComponent);
    await fixture.whenStable();
    const form = (fixture.componentInstance as unknown as { form: RegisterComponent['form'] }).form;
    expect(form.valid).toBe(false);
    form.patchValue({ companyName: 'Co', fullName: 'A B', email: 'not-an-email', password: 'short', acceptTerms: false });
    expect(form.controls.email.hasError('email')).toBe(true);
    expect(form.controls.password.hasError('minlength')).toBe(true);
    expect(form.controls.acceptTerms.hasError('required')).toBe(true);
    form.patchValue({ email: 'a@b.test', password: 'long-enough-password', acceptTerms: true });
    expect(form.valid).toBe(true);
  });

  it('mining view always filters by the MINING sector and applies chosen filters', async () => {
    setup({ sector: 'MINING' });
    const fixture = TestBed.createComponent(OpportunityListComponent);
    await fixture.whenStable();

    const initial = http.expectOne((r) => r.url === '/api/v1/opportunities');
    expect(initial.request.params.get('sector')).toBe('MINING');
    expect(initial.request.params.has('q')).toBe(false);
    initial.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    const form = (fixture.componentInstance as unknown as { form: OpportunityListComponent['form'] }).form;
    form.patchValue({ q: 'conveyor', eventType: 'RFQ', includeClosed: true });
    await new Promise((r) => setTimeout(r, 400));
    await fixture.whenStable();

    const filtered = http.expectOne((r) => r.url === '/api/v1/opportunities');
    expect(filtered.request.params.get('sector')).toBe('MINING');
    expect(filtered.request.params.get('q')).toBe('conveyor');
    expect(filtered.request.params.get('eventType')).toBe('RFQ');
    expect(filtered.request.params.get('activeOnly')).toBe('false');
    filtered.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });
});
