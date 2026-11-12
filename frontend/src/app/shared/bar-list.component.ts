import { Component, computed, input } from '@angular/core';
import { humanise } from '../core/format';
import { Bucket } from '../core/models';

/** Accessible horizontal bar chart for counts by category. */
@Component({
  selector: 'app-bar-list',
  template: `
    @if (items().length === 0) {
      <div class="muted small">No data yet.</div>
    } @else {
      <div class="bars" role="list">
        @for (b of rows(); track b.key) {
          <div class="bar-row" role="listitem" [attr.aria-label]="b.name + ': ' + b.count">
            <span class="name" [title]="b.name">{{ b.name }}</span>
            <span class="bar-track"><span class="bar-fill" [style.width.%]="b.pct"></span></span>
            <span class="count">{{ b.count }}</span>
          </div>
        }
      </div>
    }
  `,
})
export class BarListComponent {
  readonly items = input.required<Bucket[]>();
  readonly humaniseLabels = input(true);
  protected readonly rows = computed(() => {
    const max = Math.max(1, ...this.items().map((b) => b.count));
    return this.items().map((b) => ({
      key: b.key,
      name: this.humaniseLabels() ? humanise(b.label) : b.label,
      count: b.count,
      pct: Math.max(2, (b.count / max) * 100),
    }));
  });
}
