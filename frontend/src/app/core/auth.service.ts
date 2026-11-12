import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, firstValueFrom, map, of, shareReplay, tap } from 'rxjs';
import { AuthResponse, CurrentUser } from './models';

/**
 * Holds the signed-in session. The refresh token lives only in an httpOnly cookie set by the API (scripts cannot
 * read it); the short-lived access token is kept in memory. On start-up the app restores the session by calling
 * /auth/refresh, which the browser authenticates with the cookie.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly accessTokenValue = signal<string | null>(null);
  private readonly currentUser = signal<CurrentUser | null>(null);
  private refreshInFlight: Observable<string> | null = null;

  readonly user = computed(() => this.currentUser());
  readonly isAuthenticated = computed(() => this.currentUser() !== null);
  readonly isPlatformAdmin = computed(() => this.currentUser()?.platformAdmin ?? false);

  accessToken(): string | null {
    return this.accessTokenValue();
  }

  hasPermission(permission: string): boolean {
    return this.currentUser()?.permissions.includes(permission) ?? false;
  }

  /** Restores a session from the refresh cookie, if there is one. Never throws. */
  restore(): Promise<void> {
    return firstValueFrom(this.refresh().pipe(map(() => undefined), catchError(() => of(undefined))));
  }

  login(email: string, password: string): Observable<CurrentUser> {
    return this.http.post<AuthResponse>('/api/v1/auth/login', { email, password }).pipe(tap((r) => this.store(r)), map((r) => r.user));
  }

  register(body: { email: string; password: string; fullName: string; companyName: string; acceptTerms: boolean; marketingConsent: boolean }): Observable<CurrentUser> {
    return this.http.post<AuthResponse>('/api/v1/auth/register', body).pipe(tap((r) => this.store(r)), map((r) => r.user));
  }

  /** Rotates the refresh cookie and returns a new access token. Concurrent callers share one request. */
  refresh(): Observable<string> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.http.post<AuthResponse>('/api/v1/auth/refresh', {}).pipe(
        tap((r) => this.store(r)),
        map((r) => r.accessToken),
        finalize(() => (this.refreshInFlight = null)),
        shareReplay(1),
      );
    }
    return this.refreshInFlight;
  }

  reloadUser(): Observable<CurrentUser> {
    return this.http.get<CurrentUser>('/api/v1/users/me').pipe(tap((u) => this.currentUser.set(u)));
  }

  logout(): void {
    this.http.post('/api/v1/auth/logout', {}).subscribe({ error: () => undefined });
    this.clear();
    void this.router.navigate(['/login']);
  }

  clear(): void {
    this.accessTokenValue.set(null);
    this.currentUser.set(null);
  }

  private store(r: AuthResponse): void {
    this.accessTokenValue.set(r.accessToken);
    this.currentUser.set(r.user);
  }
}
