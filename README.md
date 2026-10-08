# PG Management System

A backend for running a PG (paying guest accommodation): properties, rooms and beds, tenants and their
check-ins, rent payments and maintenance complaints, plus dashboards with the main numbers.

It is a REST API only (JSON over HTTP); there is no frontend in this repository.

**Who uses it**

- **ADMIN / MANAGER** (staff): manage properties, rooms, beds, tenants, payments and maintenance, and see the dashboards.
  ADMINs also create staff accounts, change roles and switch accounts off.
- **TENANT**: a tenant's own login. A tenant can report maintenance issues, see their own issues and change their
  password, nothing else.

**Main features**

- Properties → rooms → beds, with room capacity and bed status (AVAILABLE / OCCUPIED)
- Tenants with check-in / check-out and a full occupancy history (a bed can never be double-booked)
- Rent payments: paid or pending, partial payments, duplicate-receipt protection
- Maintenance issues: reported by tenants or staff, assigned to staff, OPEN → IN_PROGRESS → RESOLVED → CLOSED
- A PG-wide dashboard and a dashboard per property, cached in Redis
- Paged lists (`?page=0&size=20`) for properties, tenants, payments and maintenance
- JWT authentication with ADMIN, MANAGER and TENANT roles; accounts are checked on every request
- Runs as three containers with Docker Compose: the API, PostgreSQL and Redis

**Why Redis?** The dashboards aggregate several tables and are opened often, but don't need to-the-millisecond
numbers, so the results are cached for a short time. Redis is only a cache: PostgreSQL is the source of
truth, and the application keeps working if Redis is down.

## Tech stack

| | |
|---|---|
| Language | Java 25 |
| Framework | Vert.x 5 (vertx-web, reactive PostgreSQL client, Redis client, auth-jwt) |
| Build | Gradle (wrapper included) |
| Database | PostgreSQL 17, schema managed by Flyway |
| Cache | Redis 7 |
| Security | JWT (HS256), BCrypt password hashing |
| Tests | JUnit 5, Mockito, Testcontainers |
| Deployment | Docker (multi-stage image) and Docker Compose |

There is no Spring, no ORM and no JDBC at request time. Database access uses the non-blocking Vert.x
PostgreSQL client with parameterized SQL. JDBC is used only by Flyway, once at startup, on a worker thread.

## Architecture

One Vert.x application (a single verticle) with a classic layered structure:

```text
src/main/java/com/pgmanager
├── Main.java / MainVerticle.java   startup, manual dependency wiring, routes
├── controller   HTTP layer: read the request, call a service, write the JSON response
├── service      business rules, validation, access rules that depend on the data
├── repository   SQL (PostgreSQL) and the Redis dashboard cache
├── model        records for database rows and enums (Role, BedStatus, ...)
├── dto          request and response bodies (including Page and the dashboards)
├── security     JWT, the account check on each request, role checks, BCrypt, security headers
├── config       environment configuration, database pool, Flyway, Redis client, JSON setup
└── exception    API exceptions and the global error handler
```

Request flow:

```text
HTTP request
 ↓
Router: security headers → (CORS, if configured) → BodyHandler
        → JwtAuthHandler (protected routes: verify the token, load the account) → RoleHandler (role check)
 ↓
Controller
 ↓
Service ──────────────→ DashboardCache (Redis)   - dashboard reads, and clearing them after writes
 ↓
Repository
 ↓
PostgreSQL
```

- Everything is asynchronous: methods return Vert.x `Future`s and nothing blocks the event loop.
  BCrypt hashing and Flyway run on worker threads.
- Writes that must succeed together (check-in, check-out, manual bed status) run in one PostgreSQL
  transaction with `SELECT ... FOR UPDATE` row locks.
- Errors from any layer go to `GlobalErrorHandler`, which always answers with the same JSON shape. Unexpected
  errors become a plain 500; database messages and stack traces only go to the log.

  ```json
  { "status": 404, "error": "NOT_FOUND", "message": "Tenant not found", "timestamp": "2026-10-09T10:15:30Z" }
  ```

## Running with Docker (recommended)

Requirements: Docker with Docker Compose.

```bash
cp .env.example .env          # then edit it - see "Environment variables" below
docker compose up --build -d  # builds the API image and starts app, postgres and redis
docker compose ps             # all three should become "healthy"
curl http://localhost:8080/api/health
# {"status":"UP","database":"UP"}
```

- **app** waits until PostgreSQL is healthy, runs the Flyway migrations, and listens on `localhost:8080`.
  If it fails to start (for example the database restarts at the same moment), Docker restarts it.
- Inside Compose the app talks to `postgres:5432` and `redis:6379` by service name. PostgreSQL and Redis are
  also published on `127.0.0.1` only (ports `DATABASE_PORT` / `REDIS_PORT`), for local tools - not to other machines.
- The app's health check calls `GET /api/health`, which answers 503 when PostgreSQL is unreachable.
  Redis is not part of it: it is only a cache.
- Redis runs without persistence, so a restart starts with an empty cache.
- `docker compose down` stops everything and keeps the database volume; `docker compose down -v` also
  **deletes all data**.

The image is built in two stages: a JDK stage runs `./gradlew installDist`, and the final image contains only a
JRE (Temurin 25), the application (`bin/pg-manager` + `lib/`) and curl for the health check. It runs as a
non-root user, and no configuration or secret is baked in.

### Running from source (development)

Requirements: JDK 25 and Docker.

```bash
cp .env.example .env
docker compose up -d postgres redis   # only the infrastructure
./gradlew run                         # on Windows: gradlew.bat run; reads .env automatically
```

If the `app` container is running, stop it first (`docker compose stop app`), because both use port 8080.

## Environment variables

All configuration comes from environment variables (see `.env.example`). `.env` is git-ignored and excluded
from the Docker build; never commit it. The application stops at startup with a clear message if a value is
missing or unsafe.

| Variable | Default | Notes |
|---|---|---|
| `APP_ENV` | `production` | `development` or `production`. Production refuses a placeholder `JWT_SECRET`. |
| `HTTP_PORT` | `8080` | |
| `DATABASE_HOST` | `localhost` | Compose sets `postgres` for the app container |
| `DATABASE_PORT` | `5432` | `.env.example` uses `5433` (host port, avoids a local PostgreSQL) |
| `DATABASE_NAME` | `pg_manager` | |
| `DATABASE_USER` | — | required |
| `DATABASE_PASSWORD` | — | required |
| `REDIS_HOST` | `localhost` | Compose sets `redis` for the app container |
| `REDIS_PORT` | `6379` | |
| `DASHBOARD_CACHE_TTL_SECONDS` | `60` | how long a dashboard stays cached |
| `JWT_SECRET` | — | required, at least 32 characters; random in production (`openssl rand -base64 48`) |
| `JWT_EXPIRATION_SECONDS` | `3600` | token lifetime |
| `ALLOW_PUBLIC_REGISTRATION` | `false` | `true` lets anyone create a MANAGER account (local testing only) |
| `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` | — | optional; creates the first ADMIN (see below) |
| `CORS_ALLOWED_ORIGIN` | — | optional; the one browser origin allowed to call the API |

`.env.example` sets `APP_ENV=development`, so its placeholder secret works locally. For a real deployment set
`APP_ENV=production` and real values. The database is configured with host, port, name, user and password
variables (there is no single database URL variable).

## First ADMIN account

Public registration is **off by default**, so the first ADMIN is created from configuration:

1. Set `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` in `.env`.
2. Start the application. If there is **no ADMIN yet**, it creates one (name "Administrator") and logs
   `Created the first ADMIN account`. If an ADMIN already exists, or the email already belongs to an account, it
   does nothing - it never turns an existing account into an ADMIN.
3. Log in, then remove the two variables from `.env` (they are ignored from now on anyway).

After that, ADMINs create the other staff accounts through the API (`POST /api/admin/users`).

Alternative without the bootstrap variables: with database access, change a user's role directly, e.g.

```bash
docker compose exec postgres psql -U pgmanager -d pg_manager \
  -c "UPDATE users SET role = 'ADMIN' WHERE email = 'you@example.com';"
```

(use your own `DATABASE_USER` / `DATABASE_NAME`). This needs an existing account, so it is mainly useful when
public registration is switched on for local testing.

## Authentication

- **Login** returns a JWT signed with HS256 (`JWT_SECRET`). Send it as `Authorization: Bearer <token>`.
  It expires after `JWT_EXPIRATION_SECONDS`.
- **Roles**
  - `ADMIN`: staff, plus the `/api/admin` endpoints.
  - `MANAGER`: staff. Created by an ADMIN (or by public registration if it is switched on).
  - `TENANT`: created by staff for one tenant (`POST /api/tenants/:tenantId/account`); only maintenance
    endpoints for their own data, plus their own password.
- **Account check on every request.** After verifying the token's signature and expiry, `JwtAuthHandler` loads
  the account (one primary-key query) and uses its *current* role. So, immediately:
  - a role change applies to the next request, even with an old token;
  - a switched-off account (`PATCH /api/admin/users/:id/active`) gets 401 "Account is disabled";
  - a deleted account (a tenant's login is deleted with the tenant) gets 401;
  - a password change makes every older token invalid (each token carries the account's token version).
- **Passwords** are hashed with BCrypt (cost 12) on a worker thread, 8 to 72 characters, never stored or returned.
- **Protection**: 401 for a missing/invalid/expired token or a disabled account, 403 for the wrong role.
  Whole path prefixes (`/api/properties*`, `/api/tenants*`, `/api/dashboard*`, `/api/admin*`, ...) are protected
  once, so new endpoints under them are protected automatically. "Is this the tenant's own issue?" is
  checked in `MaintenanceService`.
- **HTTP hardening**: every response has `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Content-Security-Policy: default-src 'none'`, `Referrer-Policy: no-referrer` and `Cache-Control: no-store`.
  CORS is off unless `CORS_ALLOWED_ORIGIN` names exactly one origin (a `*` wildcard is refused). Request bodies
  are limited to 64 KB (413). HTTPS and HSTS belong to the reverse proxy in front of the app.

### Examples

```bash
# Log in
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"email": "owner@example.com", "password": "owner-password"}'
# {"token":"eyJhbGciOiJIUzI1NiJ9..."}

TOKEN=eyJhbGciOiJIUzI1NiJ9...

# Who am I?
curl http://localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
# {"id":"...","email":"owner@example.com","role":"ADMIN","tenantId":null}

# ADMIN creates a manager
curl -X POST http://localhost:8080/api/admin/users -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Meena", "email": "meena@example.com", "password": "manager-pass-1", "role": "MANAGER"}'
# 201 {"id":"...","name":"Meena","email":"meena@example.com","role":"MANAGER","tenantId":null,"active":true}

# Change your own password (any role). The answer is a new token; all older tokens stop working.
curl -X PATCH http://localhost:8080/api/auth/password -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"currentPassword": "owner-password", "newPassword": "a-new-password"}'
# {"token":"..."}
```

Password change errors: 400 `Current password is incorrect`, 400 for an invalid new password (same rules as
everywhere), 401 without a valid token. There is no "forgot password" flow (see limitations).

## API overview

All endpoints are under `/api` and use JSON. "Staff" means ADMIN or MANAGER.

| Group | Endpoints | Who |
|---|---|---|
| Health | `GET /api/health` | public |
| Auth | `POST /api/auth/login`, `POST /api/auth/register` (403 unless enabled) | public |
| | `GET /api/auth/me`, `PATCH /api/auth/password` | any logged-in user |
| Admin | `POST /api/admin/users`, `PATCH /api/admin/users/:id/role`, `PATCH /api/admin/users/:id/active`, `GET /api/admin/test` (role check) | ADMIN |
| Properties | `POST, GET* /api/properties`, `GET, PUT, DELETE /api/properties/:id` | staff |
| Rooms | `POST, GET /api/properties/:propertyId/rooms`, `GET, PUT, DELETE /api/rooms/:id` | staff |
| Beds | `POST, GET /api/rooms/:roomId/beds`, `GET, PUT, DELETE /api/beds/:id`, `PATCH /api/beds/:id/status` | staff |
| Tenants | `POST, GET* /api/tenants`, `GET, PUT, DELETE /api/tenants/:id` | staff |
| Occupancy | `POST /api/tenants/:tenantId/check-in` (body `{"bedId"}`), `POST /api/tenants/:tenantId/check-out`, `GET /api/tenants/:tenantId/bed`, `GET /api/tenants/:tenantId/history` | staff |
| Tenant login | `POST /api/tenants/:tenantId/account` (body `{"email", "password"}`) | staff |
| Payments | `POST, GET* /api/payments` (filters `tenantId`, `status`, `rentMonth`), `GET, PUT /api/payments/:id`, `GET /api/tenants/:tenantId/payments` | staff |
| Maintenance | `POST /api/maintenance`, `GET /api/maintenance/:id`, `PUT /api/maintenance/:id` | staff, or a tenant for their own issues |
| | `GET* /api/maintenance` (filters `status`, `priority`, `category`, `tenantId`), `PATCH /api/maintenance/:id/assign`, `PATCH /api/maintenance/:id/status` | staff |
| | `GET /api/tenants/:tenantId/maintenance` | staff, or that tenant |
| Dashboards | `GET /api/dashboard`, `GET /api/properties/:propertyId/dashboard` | staff |

`*` = paged list (see Pagination). A tenant's own histories (`/api/tenants/:tenantId/...`) return plain arrays.

A few rules worth knowing:

- Deletes are blocked (409) when something still depends on the row: a property with rooms, a room with
  beds, an occupied bed, a tenant with occupancy/payment/maintenance history. History is never deleted.
- There is no delete for payments or maintenance issues; they are kept as history.
- A tenant must be checked in to report a maintenance issue. The issue remembers the bed (and through it
  the room and property) where it was reported, also after the tenant checks out.
- Maintenance status moves: OPEN → IN_PROGRESS or RESOLVED, IN_PROGRESS → RESOLVED,
  RESOLVED → CLOSED or back to OPEN (reopen). CLOSED is final.

### Request / response examples

```bash
# Create a tenant (staff)
curl -X POST http://localhost:8080/api/tenants -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Asha", "phone": "9123400000", "joiningDate": "2026-10-01", "monthlyRent": 9000, "securityDeposit": 9000}'
# 201 {"id":"...","name":"Asha",...,"status":"PENDING",...}

# Check the tenant in to a bed
curl -X POST http://localhost:8080/api/tenants/<tenantId>/check-in -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"bedId": "<bedId>"}'

# Record a payment
curl -X POST http://localhost:8080/api/payments -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"tenantId": "<tenantId>", "amount": 9000, "rentMonth": "2026-10", "status": "PAID", "paymentMethod": "UPI", "paymentDate": "2026-10-05"}'

# A tenant reports an issue with their own token (the tenant comes from the token)
curl -X POST http://localhost:8080/api/maintenance -H "Authorization: Bearer $TENANT_TOKEN" -H "Content-Type: application/json" \
  -d '{"title": "Tap leaking", "description": "Bathroom tap leaking since morning", "category": "PLUMBING", "priority": "HIGH"}'
```

### Pagination

`GET /api/properties`, `/api/tenants`, `/api/payments` and `/api/maintenance` return one page:

```bash
curl "http://localhost:8080/api/payments?status=PAID&page=1&size=20" -H "Authorization: Bearer $TOKEN"
```

```json
{ "items": [ ... ], "page": 1, "size": 20, "totalItems": 125, "totalPages": 7 }
```

- `page` starts at 0 (default 0); `size` is 1 to 100 (default 20). Anything else is a 400.
- Filters and paging combine. A page past the end has empty `items` but still the totals.
- Newest first, always with the row id as the last sort column, so the order (and each page) is stable.

### Dashboards

`GET /api/dashboard` (whole PG):

```json
{
  "properties": 3,
  "rooms": 24,
  "beds": { "total": 72, "available": 18, "occupied": 54 },
  "tenants": { "pending": 4, "active": 54, "checkedOut": 21 },
  "payments": { "paidCount": 140, "pendingCount": 18, "paidAmount": 532000.00, "pendingAmount": 72000.00 },
  "maintenance": { "open": 7, "inProgress": 3, "resolved": 22, "closed": 9, "urgent": 2 },
  "generatedAt": "2026-10-09T10:15:30.123456Z"
}
```

`GET /api/properties/:propertyId/dashboard` (one property, 404 if it doesn't exist):

```json
{
  "propertyId": "...",
  "rooms": 8,
  "beds": { "total": 24, "available": 6, "occupied": 18 },
  "tenants": { "active": 18, "checkedOut": 7 },
  "payments": { "paidCount": 40, "pendingCount": 5, "paidAmount": 150000.00, "pendingAmount": 20000.00 },
  "maintenance": { "open": 2, "inProgress": 1, "resolved": 9, "closed": 3, "urgent": 1 },
  "generatedAt": "2026-10-09T10:15:30.123456Z"
}
```

(Example values.) Definitions:

- Payment amounts are totals over all recorded payments. `urgent` = URGENT issues still OPEN or IN_PROGRESS.
- Property dashboard: `tenants.active` = checked in at this property now; `tenants.checkedOut` = stayed there
  before and isn't living there now. Tenants who never checked in belong to no property, so they only appear
  on the PG-wide dashboard. A payment counts for a property when the tenant was staying there during that
  rent month.
- `generatedAt` is when the numbers were calculated; a cached response keeps its original time.
- Each dashboard is one SQL statement of aggregates, so its numbers are consistent with each other.

## Redis caching

- **What is cached:** the dashboards only, as JSON: `dashboard:summary` (whole PG) and
  `dashboard:property:<propertyId>` (one per property).
- **Flow:** read Redis → on a hit, return it; on a miss, run the aggregate query in PostgreSQL and store it with
  `SET key json EX <ttl> NX`.
- **TTL:** `DASHBOARD_CACHE_TTL_SECONDS` (default 60).
- **Clearing after writes:** after a write commits, the PG-wide key and the keys of the properties whose numbers
  changed are set to the value `cleared` for 5 seconds (not just deleted). Other properties keep their cache.
  - properties: create, delete
  - rooms: create, delete
  - beds: create, delete, status change
  - tenants: create, delete, check-in, check-out
  - payments: create, update (the properties where the tenant stayed in that month, before and after the change)
  - maintenance: create, update (the priority may change the urgent count), status change

  Reads and changes the dashboards don't count (renames, tenant details, maintenance assignment, accounts)
  leave the cache alone. Clearing happens only after the database write succeeded, never before a transaction
  that could still roll back.
- **Why `cleared` and `NX`:** a dashboard request that started calculating *before* a write could otherwise
  store its older numbers *after* the write cleared the key. While a key says `cleared`, dashboards are
  calculated fresh but not cached, and `NX` stores a new dashboard only into an empty key.
- **If Redis is unavailable:**
  - a failed cache read → the dashboard is calculated from PostgreSQL (no attempt to write to Redis);
  - a failed cache write → the calculated dashboard is still returned;
  - a failed clear → the write still succeeds; the key is remembered and cleared again before the cache is
    used next time, and until that works the cache is skipped.

  Requests never fail because of Redis; problems are logged as warnings. The connect timeout is 1 second, so
  while Redis is unreachable a dashboard request can take about a second longer.
- **Cached JSON that can't be read** (corrupt or from an older version) is ignored and overwritten.
- The Compose Redis keeps nothing on disk: after a restart it starts empty instead of loading old dashboards.

## Running tests

```bash
./gradlew clean test
```

Docker must be running: the integration tests start throwaway PostgreSQL and Redis containers with
Testcontainers (they don't use the Compose containers) and call the real HTTP API. Some tests start extra
copies of the application, e.g. on a brand-new empty database to check that all migrations run from scratch.
The unit tests mock the repositories and the Redis client. There were 452 tests when this README was written;
all of them pass.

## Database migrations

Flyway migrations live in `src/main/resources/db/migration` and run automatically at startup:

| Version | What it does |
|---|---|
| V1 | initial schema: users, properties, rooms, beds, tenants, occupancy history, payments, maintenance |
| V2 | restrict deletes of properties with rooms and rooms with beds |
| V3 | tenant status PENDING / ACTIVE / CHECKED_OUT, positive rent |
| V4 | rent payments: receipt id, one PENDING payment per tenant and month |
| V5 | maintenance issues and TENANT logins |
| V6 | account status (`active`) and token version |
| V7 | index for the property dashboard (maintenance issues by bed) |

They run on an empty database as well as on an existing one. Never edit a migration that has already run;
add a new version instead.

## Known limitations

- **No "forgot password" / password reset.** There is no email infrastructure; a user who forgot their password
  needs an ADMIN (for staff) or the database. Users can change their own password while logged in.
- **No logout endpoint.** A token stays valid until it expires unless the password is changed or the account is
  switched off. Each authenticated request costs one small primary-key query for the account check.
- **No rate limiting** on login or password change; put it in the reverse proxy if the API is public.
- **Breaking change in Phase 8:** the four paged list endpoints now return `{items, page, ...}` instead of an
  array, and properties and tenants are listed newest first.
- **Maintenance issues need a checked-in tenant.** There are no property-wide issues (for example a broken lift).
- **Payments are matched to properties by date.** A tenant who moved between two properties during a month
  counts on both property dashboards for that month's payments.
- **Dashboard cache (single application instance assumed):** clears that failed while Redis was down are
  remembered in that instance's memory. With several app instances, or if the app restarts before Redis comes
  back, a dashboard can be out of date for up to the TTL. A dashboard calculation slower than 5 seconds could
  also still cache older numbers.
- **Paged lists count and fetch in two queries**, so `totalItems` can be off by one while rows are being added.
- **Route order matters for one route:** `GET /api/tenants/:tenantId/maintenance` (open to tenants) is
  registered in `MainVerticle` before the staff-only `/api/tenants*` guard. Keep that order when adding routes.
- **HTTPS is not handled by the app**; run it behind a reverse proxy / load balancer that terminates TLS.
