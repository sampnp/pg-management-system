# PG Management System

A backend for running a PG (paying guest accommodation): properties, rooms and beds, tenants and their
check-ins, rent payments and maintenance complaints, plus dashboards with the main numbers.

It is a REST API only (JSON over HTTP); there is no frontend in this repository. For the reasoning behind the
design, see [docs/interview-notes.md](docs/interview-notes.md).

**Who uses it**

- **ADMIN / MANAGER** (staff): manage properties, rooms, beds, tenants, payments and maintenance, and see the
  dashboards. ADMINs also create staff accounts, change roles and switch accounts off.
- **TENANT**: a tenant's own login. A tenant can report maintenance issues, see their own issues and change their
  password, nothing else.

**Main features**

- Properties → rooms → beds, with room capacity and bed status (AVAILABLE / OCCUPIED)
- Tenants with check-in / check-out and a full occupancy history (a bed can never be double-booked)
- Rent payments: paid or pending, partial payments, duplicate-receipt protection
- Maintenance issues: reported by tenants or staff, assigned to staff, OPEN → IN_PROGRESS → RESOLVED → CLOSED
- A PG-wide dashboard and a dashboard per property, cached in Redis
- Paged lists (`?page=0&size=20`) for properties, tenants, payments and maintenance
- JWT authentication with ADMIN, MANAGER and TENANT roles; the account is checked on every request;
  login is rate-limited
- Request ids and one log line per request; graceful shutdown
- Runs as three containers with Docker Compose: the API, PostgreSQL and Redis

## Architecture

One Vert.x application (a single verticle) - a modular monolith with a classic layered structure:

```text
Client (curl, Postman, a future frontend)
  |
  v
Vert.x HTTP API (one process, port 8080)
  |
  +--> Router: request log + id -> security headers -> (CORS) -> body limit
  |            -> JwtAuthHandler (token + account check) -> RoleHandler (role check)
  |
  +--> Controllers   read the request, call a service, write JSON
  |
  +--> Services      validation, business rules, transactions, "is this your own data?" checks
  |
  +--> Repositories  parameterized SQL
  |       |
  |       v
  |   PostgreSQL  (source of truth; Flyway migrations at startup)
  |
  +--> Redis      (dashboard cache + login rate limit; optional - the app works without it)
```

```text
src/main/java/com/pgmanager
├── Main.java / MainVerticle.java   startup, shutdown, manual dependency wiring, routes
├── controller   HTTP layer (+ RequestLogHandler)
├── service      business rules, validation, access rules that depend on the data
├── repository   SQL (PostgreSQL) and the Redis dashboard cache
├── model        records for database rows and enums (Role, BedStatus, ...)
├── dto          request and response bodies (including Page and the dashboards)
├── security     JWT, account check, role checks, BCrypt, login rate limiter, security headers
├── config       environment configuration, database pool, Flyway, Redis client, JSON setup
└── exception    API exceptions and the global error handler
```

- **Request flow:** controller → service → repository → PostgreSQL. Security is enforced before the controller
  (token, account, role); rules that depend on the data itself (a tenant's own issue) are checked in the service.
- **Non-blocking:** every database and Redis call returns a Vert.x `Future`; nothing blocks the event loop.
  BCrypt hashing and Flyway run on worker threads.
- **Transactions** are used only where several writes must succeed or fail together (see "Transactions").
- **Errors** from any layer go to `GlobalErrorHandler`, which always answers with the same JSON shape. Unexpected
  errors become a plain 500; exception details and stack traces only go to the log (with the request id).

  ```json
  { "status": 404, "error": "NOT_FOUND", "message": "Tenant not found", "timestamp": "2026-10-09T10:15:30Z" }
  ```

## Technology stack

| Technology | Why it is here |
|---|---|
| Java 25 | Records, pattern matching (`switch` over exceptions), text blocks for SQL |
| Vert.x 5 | Small, explicit, non-blocking HTTP server and clients; no hidden framework magic |
| Vert.x reactive PostgreSQL client | Non-blocking database access with plain parameterized SQL (no ORM, no JDBC at request time) |
| PostgreSQL 17 | Transactions, row locks, constraints and partial unique indexes keep the data correct |
| Flyway | Versioned schema migrations, run once at startup (JDBC, on a worker thread) |
| Redis 7 + Vert.x Redis client | Short-lived cache for the dashboards, counters for login rate limiting |
| JWT (HS256, vertx-auth-jwt) | Stateless login tokens |
| BCrypt (cost 12) | Slow, salted password hashing |
| JUnit 5, Mockito | Unit tests of services with mocked repositories |
| Testcontainers | Integration tests against real throwaway PostgreSQL and Redis |
| Docker / Docker Compose | Reproducible build (multi-stage image) and the three-container stack |
| slf4j-simple | Plain log lines on stderr (`docker compose logs app`) |

## Running with Docker (recommended)

Requirements: Docker with Docker Compose.

```bash
cp .env.example .env          # then edit it - see "Environment variables"
docker compose up --build -d  # builds the API image and starts app, postgres and redis
docker compose ps             # all three should become "healthy"
curl http://localhost:8080/api/health
# {"status":"UP","database":"UP"}
```

Stopping:

```bash
docker compose stop           # stop the containers, keep everything
docker compose down           # remove the containers, KEEP the database volume
docker compose down -v        # also DELETES the database volume (all data)
docker compose logs -f app    # follow the application log
```

- **app** waits until PostgreSQL is healthy, runs the Flyway migrations and listens on `localhost:8080`. If
  startup fails (for example the database restarts at that moment), Docker starts it again.
- Inside Compose the app talks to `postgres:5432` and `redis:6379` by service name. PostgreSQL and Redis are also
  published on `127.0.0.1` only (`DATABASE_PORT` / `REDIS_PORT`), for local tools - not to other machines.
- The health check calls `GET /api/health`, which answers 503 when PostgreSQL is unreachable. Redis is not part
  of it: it is optional.
- **Graceful shutdown:** `docker compose stop app` sends SIGTERM. The app stops accepting connections, lets running
  requests finish (up to 10 s), then closes the database pool and the Redis client (`stop_grace_period: 30s`).
- Redis keeps nothing on disk, so a restart starts with an empty cache.

The image is built in two stages: a JDK stage runs `./gradlew installDist`; the final image has only a JRE
(Temurin 25), the application (`bin/pg-manager` + `lib/`) and curl for the health check. It runs as a non-root
user (`app`) and contains no configuration or secrets.

### Running from source (development)

Requirements: JDK 25 and Docker.

```bash
cp .env.example .env
docker compose up -d postgres redis   # only the infrastructure (or: docker compose stop app)
./gradlew run                         # on Windows: gradlew.bat run; reads .env automatically
```

Both the app container and `./gradlew run` use port 8080, so only one of them can run at a time.

## Environment variables

All configuration comes from environment variables (see `.env.example`). `.env` is git-ignored and excluded from
the Docker build; never commit it. The application stops at startup with a clear message if a value is missing
or unsafe.

| Variable | Default | Notes |
|---|---|---|
| `APP_ENV` | `production` | `development` or `production`. Production refuses a placeholder `JWT_SECRET`. |
| `HTTP_PORT` | `8080` | |
| `DATABASE_HOST` | `localhost` | Compose sets `postgres` for the app container |
| `DATABASE_PORT` | `5432` | `.env.example` uses `5433` (host port, avoids a local PostgreSQL) |
| `DATABASE_NAME` | `pg_manager` | |
| `DATABASE_USER` / `DATABASE_PASSWORD` | — | required |
| `REDIS_HOST` | `localhost` | Compose sets `redis` for the app container |
| `REDIS_PORT` | `6379` | |
| `DASHBOARD_CACHE_TTL_SECONDS` | `60` | how long a dashboard stays cached |
| `JWT_SECRET` | — | required, at least 32 characters; random in production (`openssl rand -base64 48`) |
| `JWT_EXPIRATION_SECONDS` | `3600` | token lifetime |
| `ALLOW_PUBLIC_REGISTRATION` | `false` | `true` lets anyone create a MANAGER account (local testing only) |
| `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` | — | optional; creates the first ADMIN (see below) |
| `CORS_ALLOWED_ORIGIN` | — | optional; the one browser origin allowed to call the API |

`.env.example` sets `APP_ENV=development`, so its placeholder secret works locally. For a real deployment set
`APP_ENV=production` and real values. The log level can be raised without rebuilding:
`JAVA_OPTS="-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"`.

## First ADMIN account

Public registration is **off by default**, so the first ADMIN is created from configuration:

1. Set `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` in `.env`.
2. Start the application. If there is **no ADMIN yet**, it creates one (name "Administrator") and logs
   `Created the first ADMIN account`. If an ADMIN already exists, or the email already belongs to an account, it
   does nothing - it never turns an existing account into an ADMIN.
3. Log in, then remove the two variables from `.env` (they are ignored from now on anyway).

After that, ADMINs create the other staff accounts with `POST /api/admin/users`.

Alternative with database access (useful when public registration is switched on for local testing):

```bash
docker compose exec postgres psql -U pgmanager -d pg_manager \
  -c "UPDATE users SET role = 'ADMIN' WHERE email = 'you@example.com';"
```

## Authentication

- **Login** (`POST /api/auth/login`) returns a JWT signed with HS256 (`JWT_SECRET`), sent as
  `Authorization: Bearer <token>`. It expires after `JWT_EXPIRATION_SECONDS`.
- **Passwords** are hashed with BCrypt (cost 12) on a worker thread, 8 to 72 characters, never stored, logged or
  returned. A wrong password and an unknown email give the same 401 and take the same time.
- **Roles**
  - `ADMIN`: staff, plus `/api/admin` (create staff accounts, change roles, switch accounts off).
  - `MANAGER`: staff. Created by an ADMIN (or by public registration if switched on).
  - `TENANT`: created by staff for one tenant (`POST /api/tenants/:tenantId/account`); only their own maintenance
    data and their own password.
- **Account check on every request.** After the token's signature and expiry are verified, `JwtAuthHandler` loads
  the account (one primary-key query) and uses its *current* role. So, immediately:
  - a role change applies to the next request, even with an old token;
  - a switched-off account (`PATCH /api/admin/users/:id/active`) gets 401 "Account is disabled";
  - a deleted account (a tenant's login is deleted with the tenant) gets 401;
  - a password change (`PATCH /api/auth/password`) makes all older tokens invalid (each token carries the
    account's token version) and returns a new token.
- **Role checks:** 401 for a missing/invalid/expired token or disabled account, 403 for the wrong role. Whole path
  prefixes (`/api/properties*`, `/api/tenants*`, `/api/dashboard*`, `/api/admin*`, ...) are protected once.
- **Public registration** is controlled by `ALLOW_PUBLIC_REGISTRATION` (off: 403).

### Login rate limiting

- Failed logins are counted in Redis per **client IP + email**: key `auth:login:fail:<sha256(ip|email)>` (hashed,
  so Redis holds no email addresses), `INCR` on each failure, a 15-minute window from the first failure.
- After **5 failures** that IP + email gets **429 Too Many Requests** (with `Retry-After`) until the window ends -
  even with the right password. The check runs before BCrypt, so blocked attempts cost nothing.
- Successful logins are never counted, and a success clears the counter. No account is ever locked in PostgreSQL.
- Why IP + email: by IP alone, everyone behind a shared IP (office, proxy) would be blocked together; by email
  alone, anyone could lock a user out from anywhere. Another account from the same IP is not affected.
- **Fail-open:** if Redis is unavailable, login works without the limit and a warning is logged.

### HTTP hardening

Every response has `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer` and
`Cache-Control: no-store`. CORS is off unless `CORS_ALLOWED_ORIGIN` names exactly one origin (a `*` wildcard is
refused at startup); other origins get a normal 403 error. Request bodies are limited to 64 KB (413). HTTPS and
HSTS belong to the reverse proxy in front of the app.

### Examples

```bash
# Log in
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"email": "owner@example.com", "password": "owner-password"}'
# {"token":"eyJhbGciOiJIUzI1NiJ9..."}

TOKEN=eyJhbGciOiJIUzI1NiJ9...

curl http://localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
# {"id":"...","email":"owner@example.com","role":"ADMIN","tenantId":null}

# ADMIN creates a manager
curl -X POST http://localhost:8080/api/admin/users -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Meena", "email": "meena@example.com", "password": "manager-pass-1", "role": "MANAGER"}'
# 201 {"id":"...","name":"Meena","email":"meena@example.com","role":"MANAGER","tenantId":null,"active":true}

# Change your own password; the answer is a new token, all older tokens stop working
curl -X PATCH http://localhost:8080/api/auth/password -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"currentPassword": "owner-password", "newPassword": "a-new-password"}'
# {"token":"..."}
```

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

`*` = paged list. A tenant's own histories (`/api/tenants/:tenantId/...`) return plain arrays.

Status codes are used the same way everywhere: 201 create, 200 read/update/action, 204 delete, 400 invalid input
(including malformed JSON, ids, enums and paging values), 401 authentication, 403 role/ownership, 404 unknown id,
409 business conflict (full room, occupied bed, duplicate, invalid status move, row in use), 413 body too large,
429 too many failed logins, 500 unexpected. Unknown JSON fields are ignored.

A few rules worth knowing:

- Deletes are blocked (409) when something still depends on the row: a property with rooms, a room with beds, an
  occupied bed, a tenant with occupancy/payment/maintenance history. History is never deleted.
- There is no delete for payments or maintenance issues; they are kept as history.
- A tenant must be checked in to report a maintenance issue. The issue remembers the bed (and through it the room
  and property) where it was reported, also after the tenant checks out.
- Maintenance status moves: OPEN → IN_PROGRESS or RESOLVED, IN_PROGRESS → RESOLVED, RESOLVED → CLOSED or back to
  OPEN (reopen). CLOSED is final.

### Request examples

```bash
# Create a tenant (staff)
curl -X POST http://localhost:8080/api/tenants -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Asha", "phone": "9123400000", "joiningDate": "2026-10-01", "monthlyRent": 9000, "securityDeposit": 9000}'

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

## Pagination

`GET /api/properties`, `/api/tenants`, `/api/payments` and `/api/maintenance` return one page:

```bash
curl "http://localhost:8080/api/payments?status=PAID&page=1&size=20" -H "Authorization: Bearer $TOKEN"
```

```json
{ "items": [ ... ], "page": 1, "size": 20, "totalItems": 125, "totalPages": 7 }
```

- `page` starts at 0 (default 0); `size` is 1 to 100 (default 20); anything else is a 400.
- Filters and paging combine. A page past the end has empty `items` but still the totals.
- Newest first, always with the row id as the last sort column, so the order (and each page) is stable.
- `totalItems` comes from a separate `COUNT(*)`, so it can change by a row or two while rows are being added.

## Transactions

Transactions are used only where several writes must succeed together, or where a check must still be true when
the write happens.

- **Check-in** (one transaction): lock the tenant row (`SELECT ... FOR UPDATE`) → make sure the tenant has no open
  stay → lock the bed row → make sure it is AVAILABLE → insert the stay → bed OCCUPIED → tenant ACTIVE. Two
  check-ins for the same bed: the second waits for the first's lock, then sees OCCUPIED and gets 409.
- **Check-out** (one transaction): lock tenant → find the open stay → lock bed → close the stay → bed AVAILABLE →
  tenant CHECKED_OUT. Any failure rolls everything back.
- **Lock order is always tenant, then bed** (manual bed status changes lock only the bed), so these transactions
  can't deadlock each other.
- **Adding a bed / changing a room's capacity:** the room row is locked first, then the beds are counted - so two
  requests can't both pass the capacity check (this was a real race, fixed in Phase 9 with a regression test).
- **Database safety nets:** partial unique indexes allow at most one open stay per bed and per tenant; foreign keys
  block deleting rows that history still uses; CHECK constraints guard statuses, amounts and dates.
- **Maintenance status/assignment/edit** need no transaction: a single conditional `UPDATE ... WHERE status = <the
  status we checked>`; if another request changed it first, nothing matches and the answer is 409.
- **Cache clearing happens after the commit**, never inside a transaction that could still roll back.

## Redis

Redis has two jobs, both optional for correctness: the **dashboard cache** and the **login rate limit**.
PostgreSQL is the source of truth.

**Dashboard cache**

- Keys: `dashboard:summary` (whole PG) and `dashboard:property:<propertyId>` (one per property), values are JSON.
- Read Redis → hit: return it; miss: run the aggregate SQL and store with `SET key json EX <ttl> NX`.
- TTL: `DASHBOARD_CACHE_TTL_SECONDS` (default 60).
- **Clearing after writes:** after a write commits, the PG-wide key and the keys of the properties whose numbers
  changed are set to `cleared` for 5 seconds. Other properties keep their cache.
  - properties: create, delete · rooms: create, delete · beds: create, delete, status change
  - tenants: create, delete, check-in, check-out
  - payments: create, update (the properties where the tenant stayed in that month, before and after)
  - maintenance: create, update (priority can change the urgent count), status change
- **Stale-cache protection:** a dashboard calculated *before* a write could otherwise be stored *after* the write
  cleared the key. While a key says `cleared` dashboards are calculated fresh but not cached, and `NX` only stores
  into an empty key.
- Unreadable cached JSON is ignored and overwritten.

**When Redis is unavailable**

- Dashboards are calculated from PostgreSQL; writes still succeed; logins work without rate limiting. Problems are
  logged as warnings, never returned as errors.
- A clear that failed is remembered and repeated before the cache is used again; until then the cache is skipped.
- After a Redis failure, Redis is skipped for 5 seconds (`RedisBackoff`), so requests don't each wait for the
  1-second connect timeout during an outage.
- Measured in Docker with Redis stopped: dashboard p50 22 ms (only the first requests wait for the timeout),
  login unchanged (~0.5 s, which is BCrypt).

## Observability

- Every response carries `X-Request-Id` (the caller's value if it is short and plain, otherwise a new UUID).
- One log line per request, written when it finishes:

  ```text
  2026-10-09T09:58:12.415Z [vert.x-eventloop-thread-0] INFO RequestLogHandler - request id=final-check-1 method=GET path=/api/properties status=200 durationMs=8 user=ddafa372-...
  ```

  Only method, path, status, duration and the user id - never the query string, headers, JWT, body or passwords.
  `GET /api/health` (called by the Docker health check) is logged at DEBUG.
- Levels: INFO for startup/shutdown and requests, WARN for recoverable infrastructure problems (Redis down),
  ERROR for unexpected failures (with the request id and stack trace, server-side only).
- **Database pool:** at most 10 connections, at most 100 requests waiting for one, 5 s to get a connection or open a
  new one, idle connections closed after 5 minutes, every connection replaced after 30 minutes.

## Performance

A light smoke test (curl, 20 parallel requests) against the Docker stack with seeded data (20 properties,
2,000 beds, 2,500 tenants, 24,000 payments, 5,000 issues): health, cached dashboards and paged lists answer in about
10 ms (p50), first dashboard calculation 40-55 ms, login about 0.5 s (BCrypt cost 12, on worker threads - never
the event loop). No blocked-thread warnings. `EXPLAIN ANALYZE`: PG-wide dashboard ~14 ms, property dashboard
~11 ms, payment-to-property lookup < 1 ms. At this size PostgreSQL correctly prefers sequential scans (even V7's index is not used yet - it pays
off as `maintenance_issues` grows); a query rewrite driven by the property's tenants was tried and gave no gain,
so no change and no new index were made.

## Running tests

```bash
./gradlew clean test
```

Docker must be running: integration tests start throwaway PostgreSQL and Redis containers with Testcontainers
(they don't use the Compose containers). Some start extra copies of the application - on a brand-new empty
database, with Redis unreachable, or to test shutdown. There are concurrency tests that send many requests at the
same moment. **487 tests, all passing** at the end of Phase 9.

## Database migrations

Flyway migrations in `src/main/resources/db/migration` run automatically at startup, on an empty database as well
as on an existing one:

| Version | What it does |
|---|---|
| V1 | initial schema: users, properties, rooms, beds, tenants, occupancy history, payments, maintenance |
| V2 | restrict deletes of properties with rooms and rooms with beds |
| V3 | tenant status PENDING / ACTIVE / CHECKED_OUT, positive rent |
| V4 | rent payments: receipt id, one PENDING payment per tenant and month |
| V5 | maintenance issues and TENANT logins |
| V6 | account status (`active`) and token version |
| V7 | index for the property dashboard (maintenance issues by bed) |

Never edit a migration that has already run; add a new version instead.

## Known limitations

- **No "forgot password" / email recovery.** Users change their password while logged in; a forgotten staff
  password needs an ADMIN or the database.
- **No logout endpoint or token blacklist.** A token stays valid until it expires unless the password changes or
  the account is switched off. Each authenticated request costs one small primary-key query.
- **No HTTPS inside the app**; run it behind a reverse proxy / load balancer that terminates TLS.
- **Rate limiting uses the TCP peer address.** Behind a reverse proxy every client has the proxy's address, so the
  limit becomes effectively per email; one IP trying many different emails is not limited (add a per-IP limit at
  the proxy for that).
- **Maintenance issues need a checked-in tenant**; there are no property-wide issues.
- **Payments are matched to properties by date**: a tenant who moved during a month counts on both property
  dashboards for that month.
- **Cache retry state is in memory and assumes one application instance.** With several instances (or a restart
  while Redis is down) a dashboard can be out of date for up to the TTL; a dashboard calculation slower than 5
  seconds could still cache older numbers.
- **Pagination totals can shift** under concurrent writes (count and page are two queries).
- **Redis commands have a connect timeout but no per-command timeout**: a Redis that accepts connections but never
  answers would slow requests down.
- **Route order matters for one route:** `GET /api/tenants/:tenantId/maintenance` (open to tenants) is registered
  before the staff-only `/api/tenants*` guard in `MainVerticle`.

This is a well-tested portfolio project, not a hardened enterprise product.
