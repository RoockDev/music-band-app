# Village Band & Music School App

Web application for a village music band and its associated music school.

- **Backend**: Java 21 + Spring Boot (REST API, Spring Security, JPA/PostgreSQL). Lives in `backend/`.
- **Frontend**: Angular SPA (not started yet). Will live in `frontend/`.
- Public site (band/school info, news, concerts, contact) plus a private, role-scoped area for musicians and administrators (sheet music, internal calendar, admin panel).

Status: backend scaffolding done (empty skeleton + one smoke test), no feature logic yet.

## Running the backend locally

`JWT_SECRET` is **required** — there is no built-in default, so the app fails fast at startup
if it's unset. Generate a local one before running:

```
export JWT_SECRET=$(openssl rand -base64 32)
docker compose up postgres
mvn -f backend/pom.xml spring-boot:run
```

The API starts on `http://localhost:8080`. Server port, datasource, and mail settings are read
from env vars (`SERVER_PORT`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `MAIL_HOST`, `MAIL_PORT`,
`MAIL_USERNAME`, `MAIL_PASSWORD`) — see `backend/src/main/resources/application.yml` for the
full list and local dev defaults.

Run backend tests (uses Testcontainers, needs Docker running):

```
mvn -f backend/pom.xml test
```
