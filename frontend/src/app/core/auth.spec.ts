import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { adminGuard, authGuard } from './guards';
import { AuthResponse, CurrentUser } from './models';

const user = (platformAdmin = false): CurrentUser => ({
  userId: 'u1', email: 'a@b.test', fullName: 'Test User', timezone: 'Africa/Johannesburg', tenantId: 't1',
  tenantName: 'Test Co', role: 'TENANT_OWNER', platformAdmin, permissions: ['OPPORTUNITIES_VIEW'], memberships: [], emailVerified: true,
});
const response = (token: string, refresh: string, admin = false): AuthResponse => ({
  accessToken: token, accessTokenExpiresAt: '', refreshToken: refresh, refreshTokenExpiresAt: '', user: user(admin),
});

describe('authentication', () => {
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => http.verify());

  function signIn(admin = false): void {
    auth.login('a@b.test', 'pw').subscribe();
    http.expectOne('/api/v1/auth/login').flush(response('access-1', 'refresh-1', admin));
  }

  it('keeps the session in memory only; the refresh token is never stored by the app', () => {
    signIn();
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.user()?.tenantName).toBe('Test Co');
    expect(auth.hasPermission('OPPORTUNITIES_VIEW')).toBe(true);
    expect(auth.accessToken()).toBe('access-1');
    expect(JSON.stringify(localStorage)).not.toContain('refresh-1');
  });

  it('restores a session from the refresh cookie at start-up, and tolerates having none', async () => {
    const restored = auth.restore();
    http.expectOne('/api/v1/auth/refresh').flush(response('access-9', 'refresh-9'));
    await restored;
    expect(auth.accessToken()).toBe('access-9');
    auth.clear();
    const none = auth.restore();
    http.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    await none;
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('adds the bearer token and refreshes once on 401, then retries', () => {
    signIn();
    let result: unknown;
    TestBed.inject(HttpClient).get('/api/v1/users/me').subscribe((r) => (result = r));

    const first = http.expectOne('/api/v1/users/me');
    expect(first.request.headers.get('Authorization')).toBe('Bearer access-1');
    first.flush({}, { status: 401, statusText: 'Unauthorized' });

    const refresh = http.expectOne('/api/v1/auth/refresh');
    expect(refresh.request.body).toEqual({});
    refresh.flush(response('access-2', 'refresh-2'));

    const retry = http.expectOne('/api/v1/users/me');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-2');
    retry.flush({ ok: true });
    expect(result).toEqual({ ok: true });
  });

  it('signs out when the refresh token is rejected', () => {
    signIn();
    TestBed.inject(HttpClient).get('/api/v1/users/me').subscribe({ error: () => undefined });
    http.expectOne('/api/v1/users/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/logout').flush(null);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('guards redirect anonymous users to login and non-admins away from admin pages', () => {
    const router = TestBed.inject(Router);
    const run = (guard: typeof authGuard) =>
      TestBed.runInInjectionContext(() => guard({} as never, { url: '/opportunities' } as never));

    const anonymous = run(authGuard) as UrlTree;
    expect(router.serializeUrl(anonymous)).toBe('/login?returnUrl=%2Fopportunities');

    signIn(false);
    expect(run(authGuard)).toBe(true);
    expect(router.serializeUrl(run(adminGuard) as UrlTree)).toBe('/dashboard');
  });

  it('allows platform administrators into admin pages', () => {
    signIn(true);
    expect(TestBed.runInInjectionContext(() => adminGuard({} as never, {} as never))).toBe(true);
  });
});
