import { Component, inject, signal } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../../core/api.service';
import { DateTimePipe } from '../../core/format';
import { Task } from '../../core/models';

@Component({
  selector: 'app-tasks',
  imports: [RouterLink, MatButtonToggleModule, MatCheckboxModule, DateTimePipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div><h1>Tasks</h1><p>Open work across your bid pipeline. Add tasks from an opportunity's pipeline panel.</p></div>
        <mat-button-toggle-group [value]="mine()" (change)="mine.set($event.value); reload()" aria-label="Filter">
          <mat-button-toggle [value]="true">Mine</mat-button-toggle>
          <mat-button-toggle [value]="false">Everyone's</mat-button-toggle>
        </mat-button-toggle-group>
      </div>
      @if (error()) { <div class="error-banner">{{ error() }}</div> }
      <div class="card table-scroll" style="padding:0">
        <table class="data">
          <thead><tr><th style="width:36px"></th><th>Task</th><th>Opportunity</th><th>Assignee</th><th>Due</th></tr></thead>
          <tbody>
            @for (t of tasks(); track t.id) {
              <tr [class.overdue]="isOverdue(t)">
                <td><mat-checkbox (change)="done(t)" aria-label="Mark done" /></td>
                <td class="strong">{{ t.title }}</td>
                <td><a [routerLink]="['/opportunities', t.opportunityId]">{{ t.opportunityTitle }}</a></td>
                <td>{{ t.assigneeName ?? '—' }}</td>
                <td class="nowrap">{{ t.dueAt | dateTime }}@if (isOverdue(t)) { <span class="pill danger">Overdue</span> }</td>
              </tr>
            } @empty { <tr><td colspan="5" class="empty">No open tasks.</td></tr> }
          </tbody>
        </table>
      </div>
    </div>
  `,
  styles: `tr.overdue td { background: var(--oip-danger-bg); }`,
})
export class TasksComponent {
  private readonly api = inject(ApiService);
  protected readonly tasks = signal<Task[]>([]);
  protected readonly mine = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.reload();
  }

  protected reload(): void {
    this.api.tasks(this.mine()).subscribe({ next: (t) => this.tasks.set(t), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected done(t: Task): void {
    this.api.updateTask(t.id, 'DONE').subscribe({ next: () => this.reload(), error: (e) => this.error.set(errorMessage(e)) });
  }

  protected isOverdue(t: Task): boolean {
    return !!t.dueAt && Date.parse(t.dueAt) < Date.now();
  }
}
