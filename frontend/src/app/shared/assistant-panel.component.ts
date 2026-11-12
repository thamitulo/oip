import { Component, ElementRef, effect, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { ApiService, errorMessage } from '../core/api.service';
import { Conversation } from '../core/models';
import { UiStateService } from '../core/ui-state.service';
import { renderMarkdown } from './markdown';

interface ChatLine { role: 'USER' | 'ASSISTANT'; html: string; text: string; tools?: string[]; }

const SUGGESTIONS = [
  'Which open tenders best match my company?',
  'Show mining-related opportunities closing in the next 14 days',
  'Find electronic-submission RFQs for IT services in Gauteng',
  'What is in my bid pipeline and what is due soon?',
];

@Component({
  selector: 'app-assistant-panel',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatMenuModule, MatProgressBarModule, MatTooltipModule],
  template: `
    <div class="panel">
      <header>
        <span class="ai-badge"><mat-icon>auto_awesome</mat-icon></span>
        <div class="title">
          <div class="strong">Opportunity Assistant</div>
          <div class="xs muted">{{ ui.aiStatus()?.model ?? 'AI' }} · answers from platform data</div>
        </div>
        <span class="spacer"></span>
        <button mat-icon-button [matMenuTriggerFor]="history" matTooltip="Conversations" aria-label="Conversations"
          (menuOpened)="loadConversations()"><mat-icon>history</mat-icon></button>
        <button mat-icon-button (click)="newChat()" matTooltip="New conversation" aria-label="New conversation"><mat-icon>add_comment</mat-icon></button>
        <button mat-icon-button (click)="ui.assistantOpen.set(false)" aria-label="Close assistant"><mat-icon>close</mat-icon></button>
        <mat-menu #history="matMenu" xPosition="before">
          @for (c of conversations(); track c.id) {
            <button mat-menu-item (click)="open(c)"><span class="truncate" style="max-width:260px;display:inline-block">{{ c.title }}</span></button>
          } @empty { <div class="xs muted" style="padding:8px 16px">No previous conversations</div> }
        </mat-menu>
      </header>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }

      <div class="body" #scroller (click)="onClick($event)">
        @if (ui.aiStatus() && !ui.aiStatus()!.assistantAvailable) {
          <div class="warn-banner">
            @if (!ui.aiStatus()!.configured) { The assistant needs an AI provider key on the server (ANTHROPIC_API_KEY). }
            @else { The assistant has been switched off by the platform administrator. }
          </div>
        }
        @if (!lines().length) {
          <div class="intro">
            <p class="muted small">Ask about tenders, requirements, your matches or your pipeline. Answers come from the
              platform's data with links; check the official documents before bidding.</p>
            @for (s of suggestions; track s) {
              <button class="suggestion" (click)="send(s)" [disabled]="busy()">{{ s }}</button>
            }
          </div>
        }
        @for (l of lines(); track $index) {
          <div class="msg" [class.user]="l.role === 'USER'">
            @if (l.role === 'USER') { <div class="bubble">{{ l.text }}</div> }
            @else {
              <div class="md" [innerHTML]="l.html"></div>
              @if (l.tools?.length) { <div class="xs muted tools">Looked up: {{ l.tools!.join(', ') }}</div> }
            }
          </div>
        }
        @if (error()) { <div class="error-banner">{{ error() }}</div> }
      </div>

      <form class="composer" (ngSubmit)="send(draft)">
        <textarea [(ngModel)]="draft" name="draft" rows="2" placeholder="Ask about opportunities…" (keydown.enter)="onEnter($event)"
          [disabled]="busy()" maxlength="4000" aria-label="Message"></textarea>
        <button mat-flat-button type="submit" [disabled]="busy() || !draft.trim()" aria-label="Send"><mat-icon>send</mat-icon></button>
      </form>
    </div>
  `,
  styles: `
    .panel { display: flex; flex-direction: column; height: 100%; background: var(--oip-surface); }
    header { display: flex; align-items: center; gap: 8px; padding: 10px 8px 10px 14px; border-bottom: 1px solid var(--oip-border); }
    .ai-badge { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 8px; background: var(--oip-ai-soft); color: var(--oip-ai); }
    .ai-badge mat-icon { font-size: 17px; width: 17px; height: 17px; }
    .body { flex: 1; overflow-y: auto; padding: 14px; display: flex; flex-direction: column; gap: 12px; }
    .intro { display: flex; flex-direction: column; gap: 6px; }
    .suggestion { text-align: left; border: 1px solid var(--oip-border); background: var(--oip-surface); border-radius: 8px;
      padding: 8px 10px; font: inherit; color: var(--oip-ink-2); cursor: pointer; }
    .suggestion:hover { border-color: var(--oip-ai); background: var(--oip-ai-soft); }
    .msg { font-size: 13px; }
    .msg.user { display: flex; justify-content: flex-end; }
    .bubble { background: var(--oip-brand); color: #fff; padding: 8px 11px; border-radius: 12px 12px 2px 12px; max-width: 85%; white-space: pre-wrap; }
    .tools { margin-top: 4px; }
    .composer { display: flex; gap: 8px; padding: 10px; border-top: 1px solid var(--oip-border); align-items: flex-end; }
    textarea { flex: 1; resize: none; border: 1px solid var(--oip-border-strong); border-radius: 8px; padding: 8px 10px; font: inherit; outline: none; }
    textarea:focus { border-color: var(--oip-brand); }
  `,
})
export class AssistantPanelComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly ui = inject(UiStateService);
  protected readonly suggestions = SUGGESTIONS;
  protected readonly lines = signal<ChatLine[]>([]);
  protected readonly conversations = signal<Conversation[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected draft = '';
  private conversationId: string | null = null;
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  constructor() {
    effect(() => {
      const prompt = this.ui.assistantPrompt();
      if (prompt && this.ui.assistantOpen()) {
        this.ui.assistantPrompt.set(null);
        this.send(prompt);
      }
    });
  }

  send(text: string): void {
    const message = text.trim();
    if (!message || this.busy()) return;
    this.draft = '';
    this.error.set(null);
    this.lines.update((l) => [...l, { role: 'USER', text: message, html: '' }]);
    this.busy.set(true);
    this.scroll();
    this.api.ask(message, this.conversationId).subscribe({
      next: (r) => {
        this.conversationId = r.conversationId;
        this.lines.update((l) => [...l, { role: 'ASSISTANT', text: r.message.content, html: renderMarkdown(r.message.content), tools: r.toolsUsed }]);
        this.busy.set(false);
        this.scroll();
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }

  newChat(): void {
    this.conversationId = null;
    this.lines.set([]);
    this.error.set(null);
  }

  loadConversations(): void {
    this.api.conversations().subscribe((c) => this.conversations.set(c));
  }

  open(c: Conversation): void {
    this.api.conversation(c.id).subscribe((messages) => {
      this.conversationId = c.id;
      this.lines.set(messages.map((m) => ({ role: m.role, text: m.content, html: m.role === 'ASSISTANT' ? renderMarkdown(m.content) : '' })));
      this.scroll();
    });
  }

  protected onEnter(e: Event): void {
    const k = e as KeyboardEvent;
    if (!k.shiftKey) {
      k.preventDefault();
      this.send(this.draft);
    }
  }

  /** Internal links in assistant answers navigate inside the app. */
  protected onClick(e: MouseEvent): void {
    const a = (e.target as HTMLElement).closest('a') as HTMLAnchorElement | null;
    const href = a?.getAttribute('href') ?? '';
    if (href.startsWith('/') && !href.startsWith('//')) {
      e.preventDefault();
      void this.router.navigateByUrl(href);
    }
  }

  private scroll(): void {
    setTimeout(() => {
      const el = this.scroller()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
