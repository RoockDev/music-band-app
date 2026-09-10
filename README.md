# Village Band & Music School App

A Java-first portfolio application for managing a music band and its associated school, with a Spring Boot REST API and an implemented Angular web interface. Musicians access their events and sheet music; administrators manage users, groups, content and permissions.

This is the **generic learning and portfolio version**, not a deployed client system or a claim of production readiness. A future adaptation for a real band or school would be a separate step, with its own branding, data and deployment decisions. The interface currently uses Spanish copy and example branding.

## What is implemented

- **Backend:** users and groups, events, sheet-music collections and files, public news/gallery/videos/courses, contact submissions and audit history.
- **Access control:** JWT authentication in an HttpOnly cookie, CSRF protection, administrator permissions and per-resource access for musicians.
- **Data integrity:** PostgreSQL persistence, Flyway migrations, optimistic version checks and transactional business operations.
- **Frontend:** public pages, account activation/login/password reset, a musician workspace and administration screens connected to the API.
- **Tests:** backend unit and integration tests, including PostgreSQL Testcontainers, plus Angular unit tests. Commands and prerequisites are below; test source alone is not evidence of a verified deployment.

## Architecture and code tour

The backend is a **single Spring Boot application organized by feature**, with controller, service and repository layers. Services depend on Spring Data repositories and entities use JPA annotations: this is not a framework-independent hexagonal architecture. File storage and email delivery have dedicated interfaces so their implementations can be replaced.

| Start here | What to inspect |
| --- | --- |
| [Events](backend/src/main/java/com/banda/events/) | Controllers, transactional services, DTOs and JPA repositories |
| [Security](backend/src/main/java/com/banda/security/) | Cookie authentication, CSRF and permissions |
| [Sheet music](backend/src/main/java/com/banda/sheetmusic/) | File lifecycle and resource-scoped access |
| [Backend tests](backend/src/test/java/com/banda/) | Unit tests and integration scenarios |
| [Angular routes](frontend/src/app/app.routes.ts) | Lazy-loaded public, musician and admin screens |
| [Angular features](frontend/src/app/features/) | UI features and API integration |

**Stack:** Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA, PostgreSQL 16, Flyway, Maven, Angular 22, TypeScript and Docker Compose.

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

Create a private, Git-ignored database password once, export it into the current shell, and
then start the local services:

```bash
if [ ! -f .env ]; then
  umask 077
  printf 'DB_PASSWORD=%s\n' "$(openssl rand -base64 24)" > .env
fi
set -a
. ./.env
set +a
docker compose up -d postgres mailpit
```

PostgreSQL listens only on `127.0.0.1:5432`. Mailpit accepts SMTP on
`127.0.0.1:1025` and exposes its inbox at <http://127.0.0.1:8025>; none of these
development ports are published to the LAN.

#### Existing PostgreSQL volumes

Changing `.env` does not rotate a database that was already initialized. Start an existing volume
with its current password, rotate it interactively, and then store the new value in `.env`:

```bash
docker compose exec postgres psql -U banda -d banda -c '\password banda'
```

### 2. Start the backend

The backend fails fast when JWT and mail settings are missing. Plain HTTP development also requires a non-secure auth cookie.

```bash
set -a
. ./.env
set +a
export JWT_SECRET="$(openssl rand -base64 32)"
export MAIL_HOST=localhost
export MAIL_PORT=1025
export MAIL_FROM=no-reply@banda.local
export FRONTEND_BASE_URL=http://localhost:4200
export APP_SECURITY_COOKIE_SECURE=false
mvn -f backend/pom.xml spring-boot:run
```

The API starts at <http://localhost:8080>. The database URL and username default to the local
Compose service, while `DB_PASSWORD` is required. Additional overrides are documented in
`backend/src/main/resources/application.yml`.

Photographs and sheet music are stored by default in the stable user directory
`${user.home}/.music-band-app/files` (normally `~/.music-band-app/files`). The backend creates
it on startup, so local development does not require `APP_FILE_STORAGE_BASE_DIR`. To use a
different writable directory, set the override before starting the backend:

```bash
export APP_FILE_STORAGE_BASE_DIR=/absolute/path/to/music-band-app-files
```

`FRONTEND_BASE_URL` is required because password-reset emails derive their
`/restablecer?token=...` link from that installation-specific origin. Production values must
use HTTPS; plain HTTP is accepted only for `localhost` or another loopback address. The reset
token lifetime can be changed with `RESET_TOKEN_TTL` (ISO-8601 duration, default `PT1H`). Reset
requests also have a one-second response-time floor to reduce account-enumeration timing signals;
deployments can tune it with `PASSWORD_RESET_MIN_RESPONSE_TIME` after measuring normal SMTP latency.

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

### First administrator and demo data

A fresh database has no seeded users or demo content. There is currently **no supported first-administrator bootstrap command** and no published default credentials. Existing administrators can manage subsequent accounts, but this does not solve first-time provisioning. Starting the services lets you inspect the public interface; it does not by itself provide access to private areas. A safe initial provisioning workflow is still needed for a self-service demo.

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

## Current scope and limitations

- No hosted demo or production deployment is provided here. Hosting, backups and operational monitoring require separate work.
- Some listings load and filter records in memory, and file retrieval loads full files. These are small-scale choices, not claims of high-volume scalability.
- Public pages start without database content; private-area exploration needs securely provisioned accounts as noted above.

## Branding

Installation-specific name, contact details, colors, social links, and asset paths live in `frontend/src/app/core/config/brand.config.ts`. Replace the corresponding SVG assets in `frontend/public/brand/` when adapting the application for another organization.
