import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { Router, RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { DateTimePipe, LabelPipe } from '../../core/format';
import { AppNotification } from '../../core/models';
import { UiStateService } from '../../core/ui-state.service';

const ICONS: Record<string, string> = {
  NEW_MATCH: 'auto_awesome', CLOSING_SOON: 'schedule', CLOSING_DATE_CHANGED: 'event_repeat', NEW_DOCUMENT: 'note_add',
  STATUS_CHANGED: 'published_with_changes', OPPORTUNITY_CHANGED: 'edit_note', ASSIGNED: 'person_add', TASK_DUE: 'task_alt',
  SOURCE_FAILURE: 'report',
};

@Component({
  selector: 'app-notifications',
  imports: [RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, DateTimePipe, LabelPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div><h1>Notifications</h1><p>New matches, closing reminders, changes at the source and assignments.
          <a routerLink="/settings">Notification settings</a></p></div>
        <div class="row">
          <mat-button-toggle-group [value]="unreadOnly()" (change)="unreadOnly.set($event.value); reload()" aria-label="Filter">
            <mat-button-toggle [value]="false">All</mat-button-toggle><mat-button-toggle [value]="true">Unread</mat-button-toggle>
          </mat-button-toggle-group>
          <button mat-stroked-button (click)="readAll()">Mark all read</button>
        </div>
      </div>
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      <div class="list">
        @for (n of items(); track n.id) {
          <button class="note" [class.unread]="!n.readAt" (click)="open(n)">
            <mat-icon>{{ icon(n.type) }}</mat-icon>
            <div class="grow">
              <div class="strong">{{ n.title }}</div>
              @if (n.body) { <div class="small muted">{{ n.body }}</div> }
              <div class="xs muted">{{ n.type | label }} · {{ n.createdAt | dateTime }}</div>
            </div>
            @if (!n.readAt) { <span class="dot" aria-label="Unread"></span> }
          </button>
        } @empty { <div class="empty">No notifications.</div> }
      </div>
    </div>
  `,
  styles: `
    .note { display: flex; gap: 12px; align-items: flex-start; width: 100%; text-align: left; border: 0; background: var(--oip-surface); padding: 12px 14px; font: inherit; cursor: pointer; }
    .note:hover { background: #fafbfc; }
    .note.unread { background: var(--oip-info-bg); }
    .note mat-icon { color: var(--oip-brand); }
    .grow { flex: 1; }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--oip-info); margin-top: 6px; }
  `,
})
export class NotificationsComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly ui = inject(UiStateService);
  protected readonly items = signal<AppNotification[]>([]);
  protected readonly unreadOnly = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.reload();
  }

  protected icon(type: string): string {
    return ICONS[type] ?? 'notifications';
  }

  protected reload(): void {
    this.api.notifications(this.unreadOnly()).subscribe({ next: (n) => this.items.set(n), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected open(n: AppNotification): void {
    if (!n.readAt) this.api.markRead(n.id).subscribe(() => this.ui.refreshUnread());
    if (n.link) void this.router.navigateByUrl(n.link);
  }

  protected readAll(): void {
    this.api.markAllRead().subscribe(() => { this.ui.unread.set(0); this.reload(); });
  }
}
