# Village Band & Music School App

A full-stack web application for a music band and its associated school. It provides an editorial public website, role-based authentication, a musician workspace for events and sheet music, and an administration area for users, groups, calendars, content, files, and audit history.

## Repository structure

- `backend/` — Java 21 and Spring Boot REST API with security, PostgreSQL persistence, mail notifications, file storage, and Testcontainers integration tests.
- `frontend/` — Angular SPA with public pages and guarded `MUSICIAN` and `ADMIN` areas. Development `/api` requests use `frontend/proxy.conf.json`.
- `docker-compose.yml` — local PostgreSQL and Mailpit services.
- `postman/` — collection for manual API exploration.

## Requirements

- Java 21 and Maven
- Docker with Compose
- Node.js supported by the locked Angular CLI: `^22.22.3`, `^24.15.0`, or `>=26`
- npm 8+

## Run locally

Run all commands from the repository root.

### 1. Start PostgreSQL and Mailpit

```bash
docker compose up -d postgres mailpit
```

PostgreSQL listens on `localhost:5432`. Mailpit accepts SMTP on `localhost:1025` and exposes its inbox at <http://localhost:8025>.

### 2. Start the backend

The backend fails fast when JWT and mail settings are missing. Plain HTTP development also requires a non-secure auth cookie.

```bash
mkdir -p /tmp/music-band-app-files
export JWT_SECRET="$(openssl rand -base64 32)"
export MAIL_HOST=localhost
export MAIL_PORT=1025
export MAIL_FROM=no-reply@banda.local
export APP_SECURITY_COOKIE_SECURE=false
export APP_FILE_STORAGE_BASE_DIR=/tmp/music-band-app-files
mvn -f backend/pom.xml spring-boot:run
```

The API starts at <http://localhost:8080>. Database defaults match `docker-compose.yml`; additional overrides are documented in `backend/src/main/resources/application.yml`.

On a new, empty database, Flyway applies the versioned migrations before Hibernate validates
the resulting schema. Hibernate never updates the schema at runtime.

#### Adopt an existing database once

Flyway intentionally refuses a non-empty database without migration history. Before adopting
one, back it up and compare its tables, columns, types, nullability, keys, checks, and indexes
with `backend/src/main/resources/db/migration/V1__initial_schema.sql`. Only if they already
match, start the backend once with:

```bash
FLYWAY_BASELINE_ON_MIGRATE=true mvn -f backend/pom.xml spring-boot:run
```

Stop that process after a successful startup, unset the variable, and use normal startup from
then on. Baseline records V1 as already applied; it does **not** execute V1 or verify/fix the
existing schema. Using it on a divergent schema can hide missing constraints or indexes until
a later migration or runtime operation fails.

### 3. Start the frontend

In another terminal:

```bash
npm --prefix frontend ci
npm --prefix frontend start
```

Open <http://localhost:4200>. Angular proxies `/api` requests to the backend, preserving the same-origin cookie flow used by authentication and CSRF protection.

## Verification

```bash
# Frontend unit tests and production build
npm --prefix frontend test -- --watch=false
npm --prefix frontend run build

# Backend compilation without tests
mvn -f backend/pom.xml -DskipTests compile

# Complete backend test suite
mvn -f backend/pom.xml test
```

The complete backend suite uses Testcontainers and therefore requires a running Docker daemon.

## Branding

Installation-specific name, contact details, colors, social links, and asset paths live in `frontend/src/app/core/config/brand.config.ts`. Replace the corresponding SVG assets in `frontend/public/brand/` when adapting the application for another organization.
