import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, interval, map, startWith } from 'rxjs';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { DateTimePipe, LabelPipe } from '../core/format';
import { AppNotification } from '../core/models';
import { UiStateService } from '../core/ui-state.service';
import { AssistantPanelComponent } from '../shared/assistant-panel.component';

interface NavItem { label: string; icon: string; link: string; exact?: boolean; }

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule, MatSidenavModule, MatIconModule, MatButtonModule, MatMenuModule,
    MatTooltipModule, MatBadgeModule, LabelPipe, DateTimePipe, AssistantPanelComponent],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly ui = inject(UiStateService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly isMobile = toSignal(
    inject(BreakpointObserver).observe('(max-width: 1024px)').pipe(map((r) => r.matches)), { initialValue: false });
  protected readonly navOpen = signal(false);
  protected readonly notifications = signal<AppNotification[]>([]);
  protected search = '';
  protected readonly initials = computed(() =>
    (this.auth.user()?.fullName ?? '?').split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase());

  protected readonly discover: NavItem[] = [
    { label: 'Dashboard', icon: 'space_dashboard', link: '/dashboard' },
    { label: 'Recommended', icon: 'auto_awesome', link: '/recommended' },
    { label: 'My focus', icon: 'center_focus_strong', link: '/focus' },
    { label: 'All opportunities', icon: 'travel_explore', link: '/opportunities' },
    { label: 'Government tenders', icon: 'account_balance', link: '/government' },
    { label: 'Mining', icon: 'landscape', link: '/mining' },
    { label: 'Organisations', icon: 'domain', link: '/organisations' },
  ];
  protected readonly work: NavItem[] = [
    { label: 'Bid pipeline', icon: 'view_kanban', link: '/pipeline' },
    { label: 'Tasks', icon: 'task_alt', link: '/tasks' },
    { label: 'Notifications', icon: 'notifications', link: '/notifications' },
    { label: 'Reports', icon: 'insights', link: '/reports' },
  ];
  protected readonly company: NavItem[] = [
    { label: 'Company profile', icon: 'business_center', link: '/profile' },
    { label: 'Team', icon: 'group', link: '/team' },
    { label: 'Settings', icon: 'tune', link: '/settings' },
  ];
  protected readonly admin: NavItem[] = [
    { label: 'Sources', icon: 'hub', link: '/admin/sources' },
    { label: 'Crawl monitoring', icon: 'monitor_heart', link: '/admin/crawls' },
    { label: 'AI settings', icon: 'psychology', link: '/admin/ai' },
  ];

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.ui.loadAiStatus();
    interval(60_000).pipe(startWith(0), takeUntilDestroyed(destroyRef)).subscribe(() => this.ui.refreshUnread());
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd), takeUntilDestroyed(destroyRef))
      .subscribe(() => this.navOpen.set(false));
  }

  protected doSearch(): void {
    const q = this.search.trim();
    void this.router.navigate(['/opportunities'], { queryParams: q ? { q, sort: 'RELEVANCE' } : {} });
  }

  protected loadNotifications(): void {
    this.api.notifications().subscribe((n) => this.notifications.set(n.slice(0, 8)));
  }

  protected openNotification(n: AppNotification): void {
    if (!n.readAt) {
      this.api.markRead(n.id).subscribe(() => this.ui.refreshUnread());
    }
    if (n.link) void this.router.navigateByUrl(n.link);
  }

  protected markAllRead(): void {
    this.api.markAllRead().subscribe(() => {
      this.ui.unread.set(0);
      this.loadNotifications();
    });
  }
}
