import { COMMA, ENTER } from '@angular/cdk/keycodes';
import { Component, input, model } from '@angular/core';
import { MatChipInputEvent, MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';

/** Editable list of short text values (services, products, industries ...). */
@Component({
  selector: 'app-chip-list-input',
  imports: [MatChipsModule, MatFormFieldModule, MatIconModule],
  template: `
    <mat-form-field appearance="outline" class="full">
      <mat-label>{{ label() }}</mat-label>
      <mat-chip-grid #grid [attr.aria-label]="label()" [disabled]="disabled()">
        @for (v of values(); track v) {
          <mat-chip-row (removed)="remove(v)">{{ v }}
            <button matChipRemove [attr.aria-label]="'Remove ' + v"><mat-icon>cancel</mat-icon></button>
          </mat-chip-row>
        }
        <input [placeholder]="placeholder()" [matChipInputFor]="grid" [matChipInputSeparatorKeyCodes]="separators"
          (matChipInputTokenEnd)="add($event)" />
      </mat-chip-grid>
      @if (hint()) { <mat-hint>{{ hint() }}</mat-hint> }
    </mat-form-field>
  `,
})
export class ChipListInputComponent {
  readonly label = input.required<string>();
  readonly placeholder = input('Type and press Enter');
  readonly hint = input<string>('');
  readonly disabled = input(false);
  readonly values = model<string[]>([]);
  protected readonly separators = [ENTER, COMMA];

  protected add(e: MatChipInputEvent): void {
    const v = (e.value ?? '').trim();
    if (v && !this.values().some((x) => x.toLowerCase() === v.toLowerCase())) {
      this.values.update((list) => [...list, v]);
    }
    e.chipInput.clear();
  }

  protected remove(v: string): void {
    this.values.update((list) => list.filter((x) => x !== v));
  }
}
