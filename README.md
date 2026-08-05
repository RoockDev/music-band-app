# Village Band & Music School App

Web application for a village music band and its associated music school.

- **Backend**: Java 21 + Spring Boot (REST API, Spring Security, JPA/PostgreSQL). Lives in `backend/`.
- **Frontend**: Angular SPA (not started yet). Will live in `frontend/`.
- Public site (band/school info, news, concerts, contact) plus a private, role-scoped area for musicians and administrators (sheet music, internal calendar, admin panel).

Status: backend scaffolding done (empty skeleton + one smoke test), no feature logic yet.

## Running the backend locally

`JWT_SECRET`, `MAIL_HOST`, `MAIL_PORT`, and `MAIL_FROM` are all **required** — none has a
built-in default, so the app fails fast at startup if any is unset. (`MAIL_HOST`/`MAIL_PORT`/
`MAIL_FROM` used to have local-dev-friendly defaults, but that meant a prod deployment that
forgot to set them would boot fine and silently blackhole every admin contact-form
notification against an unreachable `localhost:1025` — see `application.yml` for the full
rationale.) Generate/set them before running:

```
export JWT_SECRET=$(openssl rand -base64 32)
export MAIL_HOST=localhost
export MAIL_PORT=1025
export MAIL_FROM=no-reply@banda.local
docker compose up postgres mailpit
mvn -f backend/pom.xml spring-boot:run
```

The API starts on `http://localhost:8080`. Server port, datasource, and mail settings are read
from env vars (`SERVER_PORT`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `MAIL_HOST`, `MAIL_PORT`,
`MAIL_USERNAME`, `MAIL_PASSWORD`) — see `backend/src/main/resources/application.yml` for the
full list and local dev defaults.

The JWT access-token cookie is `Secure` by default (requires HTTPS). If you're running the
API over plain HTTP locally (no TLS reverse proxy), set `APP_SECURITY_COOKIE_SECURE=false` —
otherwise the browser will silently drop the cookie and login will appear to do nothing.

Run backend tests (uses Testcontainers, needs Docker running):

```
mvn -f backend/pom.xml test
```
