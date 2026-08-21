# Frontend

Angular standalone SPA for the public website and authenticated member areas.

## Local development

Requirements: Node 26+, npm 11+, and the Spring Boot backend running on port `8080`.

```bash
npm install
npm start
```

Open `http://localhost:4200`. The Angular development server proxies `/api` to
`http://localhost:8080`, preserving a same-origin browser contract for the httpOnly JWT cookie
and Angular's default `XSRF-TOKEN` / `X-XSRF-TOKEN` handling.

```bash
npm test -- --watch=false
npm run build
```

## White-label customization

Edit `src/app/core/config/brand.config.ts` to replace the product name, tagline, colors,
contact details, social links, and image paths. Replace the generic files under `public/brand/`
with installation-specific artwork while keeping the configured paths stable.

Do not scatter installation-specific names or colors through components. New brand values belong
in `BrandConfig` and should be exposed through `BrandService`.
