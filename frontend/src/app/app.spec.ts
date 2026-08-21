import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { BRAND_CONFIG, brandConfig } from './core/config/brand.config';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), { provide: BRAND_CONFIG, useValue: brandConfig }],
    }).compileComponents();
  });

  it('creates the application and applies the configured accent', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    expect(fixture.componentInstance).toBeTruthy();
    expect(document.documentElement.style.getPropertyValue('--color-accent')).toBe(
      brandConfig.colors.accent,
    );
  });
});
