import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found-page',
  imports: [RouterLink],
  template: `
    <section class="simple-page centered">
      <p class="eyebrow">404 / Fuera de partitura</p>
      <h1>Esta página no forma parte del archivo.</h1>
      <a class="button" routerLink="/">Volver al inicio</a>
    </section>
  `,
})
export class NotFoundPage {}
