import { Component, input, output } from '@angular/core';

export type PageStateKind = 'loading' | 'empty' | 'error';

@Component({
  selector: 'app-page-state',
  template: `
    @if (kind() === 'loading') {
      <div class="state-panel" role="status" aria-live="polite">
        <p class="eyebrow">Cargando</p>
        <p>{{ loadingText() }}</p>
      </div>
    } @else if (kind() === 'empty') {
      <div class="state-panel" role="status">
        <p class="eyebrow">Archivo abierto</p>
        <p>{{ emptyText() }}</p>
      </div>
    } @else {
      <div class="state-panel" role="alert">
        <p class="eyebrow">No se pudo cargar</p>
        <p>{{ errorText() }}</p>
        <button class="button secondary" type="button" (click)="retry.emit()">Intentar de nuevo</button>
      </div>
    }
  `,
})
export class PageState {
  readonly kind = input.required<PageStateKind>();
  readonly loadingText = input('Consultando el archivo…');
  readonly emptyText = input('Todavía no hay contenido publicado.');
  readonly errorText = input('El contenido no está disponible en este momento.');
  readonly retry = output<void>();
}
