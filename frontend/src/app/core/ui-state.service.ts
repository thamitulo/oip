import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { AiStatus } from './models';

/** App-wide UI state: assistant drawer, AI availability and unread notification count. */
@Injectable({ providedIn: 'root' })
export class UiStateService {
  private readonly api = inject(ApiService);
  readonly assistantOpen = signal(false);
  readonly assistantPrompt = signal<string | null>(null);
  readonly aiStatus = signal<AiStatus | null>(null);
  readonly unread = signal(0);

  loadAiStatus(): void {
    this.api.aiStatus().subscribe({ next: (s) => this.aiStatus.set(s), error: () => this.aiStatus.set(null) });
  }

  refreshUnread(): void {
    this.api.unreadCount().subscribe({ next: (r) => this.unread.set(r.count), error: () => undefined });
  }

  /** Opens the assistant, optionally with a question to ask straight away. */
  ask(prompt?: string): void {
    this.assistantPrompt.set(prompt ?? null);
    this.assistantOpen.set(true);
  }
}
