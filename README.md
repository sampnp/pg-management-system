# PG Management System

A backend for running a PG (paying guest accommodation): properties, rooms and beds, tenants and their
check-ins, rent payments and maintenance complaints, plus a dashboard with the main numbers.

It is a REST API only (JSON over HTTP); there is no frontend in this repository.

**Who uses it**

- **ADMIN / MANAGER** (staff): manage properties, rooms, beds, tenants, payments and maintenance, and see the dashboard.
- **TENANT**: a tenant's own login. A tenant can report maintenance issues and see their own issues, nothing else.

**Main features**

- Properties → rooms → beds, with room capacity and bed status (AVAILABLE / OCCUPIED)
- Tenants with check-in / check-out and a full occupancy history (a bed can never be double-booked)
- Rent payments: paid or pending, partial payments, duplicate-receipt protection
- Maintenance issues: reported by tenants or staff, assigned to staff, OPEN → IN_PROGRESS → RESOLVED → CLOSED
- Dashboard with PG-wide statistics, cached in Redis
- JWT authentication with ADMIN, MANAGER and TENANT roles

**Why Redis?** The dashboard aggregates several tables and is opened often, but does not need
to-the-millisecond numbers. Its result is cached in Redis for a short time. Redis is only a cache:
PostgreSQL is the source of truth, and the application keeps working if Redis is down.

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
| Local infrastructure | Docker Compose (PostgreSQL + Redis) |

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
├── dto          request and response bodies
├── security     JWT creation/verification, JWT middleware, role checks, BCrypt
├── config       environment configuration, database pool, Flyway, Redis client, JSON setup
└── exception    API exceptions and the global error handler
```

Request flow:

```text
HTTP request
 ↓
Router: BodyHandler → JwtAuthHandler (protected routes) → RoleHandler (role check)
 ↓
Controller
 ↓
Service ──────────────→ DashboardCache (Redis)   - dashboard reads, and clearing the cache after writes
 ↓
Repository
 ↓
PostgreSQL
```

- Everything is asynchronous: methods return Vert.x `Future`s and nothing blocks the event loop.
  BCrypt hashing and Flyway run on worker threads.
- Writes that must succeed together (check-in, check-out, manual bed status) run in one PostgreSQL
  transaction with `SELECT ... FOR UPDATE` row locks.
- Errors from any layer go to `GlobalErrorHandler`, which always answers with the same JSON shape:

  ```json
  { "status": 404, "error": "NOT_FOUND", "message": "Tenant not found", "timestamp": "2026-10-09T10:15:30Z" }
  ```

## Local setup

Requirements: JDK 25, Docker (with Docker Compose).

```bash
cp .env.example .env        # then edit .env: set DATABASE_PASSWORD and a long random JWT_SECRET
docker compose up -d        # starts PostgreSQL and Redis
./gradlew run               # on Windows: gradlew.bat run
```

`./gradlew run` reads `.env` automatically. Flyway creates/updates the schema on startup.
Check that it is running:

```bash
curl http://localhost:8080/api/health
# {"status":"UP","database":"UP"}
```

### Environment variables

All configuration comes from environment variables (see `.env.example`). `.env` is git-ignored; never commit it.

| Variable | Default | Notes |
|---|---|---|
| `HTTP_PORT` | `8080` | |
| `DATABASE_HOST` | `localhost` | |
| `DATABASE_PORT` | `5432` | `.env.example` uses `5433` so it doesn't clash with a locally installed PostgreSQL |
| `DATABASE_NAME` | `pg_manager` | |
| `DATABASE_USER` | — | required |
| `DATABASE_PASSWORD` | — | required |
| `REDIS_HOST` | `localhost` | |
| `REDIS_PORT` | `6379` | |
| `DASHBOARD_CACHE_TTL_SECONDS` | `60` | how long the dashboard stays cached |
| `JWT_SECRET` | — | required, at least 32 characters |
| `JWT_EXPIRATION_SECONDS` | `3600` | token lifetime |

The application stops at startup with a clear message if a required variable is missing.

## First ADMIN account

- Public registration (`POST /api/auth/register`) always creates a **MANAGER**. A `role` field in the
  request is ignored, so nobody can make themselves ADMIN through the API.
- The **first ADMIN** is therefore created by someone with database access: register normally, then
  change the role in PostgreSQL. With the Docker Compose setup and the values from `.env.example`
  (use your own `DATABASE_USER` / `DATABASE_NAME` if you changed them):

  ```bash
  docker compose exec postgres psql -U pgmanager -d pg_manager \
    -c "UPDATE users SET role = 'ADMIN' WHERE email = 'you@example.com';"
  ```

  Then log in again: the new role is in the next token.
- After that, an ADMIN promotes other users through the API:

  ```bash
  curl -X PATCH http://localhost:8080/api/admin/users/<user-id>/role \
    -H "Authorization: Bearer <admin token>" -H "Content-Type: application/json" \
    -d '{"role": "ADMIN"}'
  ```

  Only `ADMIN` and `MANAGER` can be given this way. An admin can't change their own role, and tenant
  accounts can't be changed into staff.

## API overview

All endpoints are under `/api` and use JSON. "Staff" means ADMIN or MANAGER.

| Group | Endpoints | Who |
|---|---|---|
| Health | `GET /api/health` | public |
| Auth | `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me` | public / logged in (`me`) |
| Admin | `PATCH /api/admin/users/:id/role`, `GET /api/admin/test` (simple role check) | ADMIN |
| Properties | `POST, GET /api/properties`, `GET, PUT, DELETE /api/properties/:id` | staff |
| Rooms | `POST, GET /api/properties/:propertyId/rooms`, `GET, PUT, DELETE /api/rooms/:id` | staff |
| Beds | `POST, GET /api/rooms/:roomId/beds`, `GET, PUT, DELETE /api/beds/:id`, `PATCH /api/beds/:id/status` | staff |
| Tenants | `POST, GET /api/tenants`, `GET, PUT, DELETE /api/tenants/:id` | staff |
| Occupancy | `POST /api/tenants/:tenantId/check-in` (body `{"bedId"}`), `POST /api/tenants/:tenantId/check-out`, `GET /api/tenants/:tenantId/bed`, `GET /api/tenants/:tenantId/history` | staff |
| Tenant login | `POST /api/tenants/:tenantId/account` (body `{"email", "password"}`) | staff |
| Payments | `POST, GET /api/payments` (filters `tenantId`, `status`, `rentMonth`), `GET, PUT /api/payments/:id`, `GET /api/tenants/:tenantId/payments` | staff |
| Maintenance | `POST /api/maintenance`, `GET /api/maintenance/:id`, `PUT /api/maintenance/:id` | staff, or a tenant for their own issues |
| | `GET /api/maintenance` (filters `status`, `priority`, `category`, `tenantId`), `PATCH /api/maintenance/:id/assign`, `PATCH /api/maintenance/:id/status` | staff |
| | `GET /api/tenants/:tenantId/maintenance` | staff, or that tenant |
| Dashboard | `GET /api/dashboard` | staff |

A few rules worth knowing:

- Deletes are blocked (409) when something still depends on the row: a property with rooms, a room with
  beds, an occupied bed, a tenant with occupancy/payment/maintenance history. History is never deleted.
- There is no delete for payments or maintenance issues; they are kept as history.
- A tenant must be checked in to report a maintenance issue. The issue remembers the bed (and through it
  the room and property) where it was reported, also after the tenant checks out.
- Maintenance status moves: OPEN → IN_PROGRESS or RESOLVED, IN_PROGRESS → RESOLVED,
  RESOLVED → CLOSED or back to OPEN (reopen). CLOSED is final.

### Dashboard response

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

(Example values.) Payment amounts are totals over all recorded payments. `urgent` counts URGENT issues
that still need work (OPEN or IN_PROGRESS). `generatedAt` is when the numbers were calculated; a cached
response keeps its original time. All numbers come from one SQL statement, so they are consistent with each other.

## Authentication

- **Login** (`POST /api/auth/login`) returns a JWT signed with HS256 (`JWT_SECRET`). Send it as
  `Authorization: Bearer <token>`. Claims: user id (`sub`), `email`, `role`, and `tenantId` for tenant logins.
  Tokens expire after `JWT_EXPIRATION_SECONDS`.
- **Roles**
  - `MANAGER`: staff; created by public registration.
  - `ADMIN`: staff, plus the `/api/admin` endpoints (role changes).
  - `TENANT`: created by staff for a specific tenant (`POST /api/tenants/:tenantId/account`). Linked to
    that tenant; only maintenance endpoints for their own data.
- **Protection**: `JwtAuthHandler` rejects missing/invalid/expired tokens with 401. `RoleHandler` rejects
  the wrong role with 403. Whole path prefixes (`/api/properties*`, `/api/tenants*`, `/api/dashboard*`, ...)
  are protected once, so new endpoints under them are protected automatically. "Is this the tenant's own
  issue?" is checked in `MaintenanceService`.
- **Passwords** are hashed with BCrypt (cost 12) on a worker thread. They are never stored or returned in
  plain text. Length: 8 to 72 bytes (BCrypt's limit).
- **Limitation:** a JWT can't be revoked. A role change (or deleting a tenant's login) does not affect a
  token that was already issued; the new role takes effect when the user logs in again, and an old token
  stays valid until it expires.

## Redis caching

- **What is cached:** only the dashboard (`GET /api/dashboard`), under the single key `dashboard:summary`,
  as JSON (written and read with the application's Jackson setup).
- **Flow:** read Redis → on a hit, return it; on a miss, run the aggregate query in PostgreSQL, store the
  result with `SET dashboard:summary <json> EX <ttl>`, and return it.
- **TTL:** `DASHBOARD_CACHE_TTL_SECONDS` (default 60).
- **Invalidation:** writes that change a number on the dashboard delete the key before the response is
  sent, so the next dashboard request recalculates:
  - properties: create, delete
  - rooms: create, delete
  - beds: create, delete, status change
  - tenants: create, delete, check-in, check-out
  - payments: create, update
  - maintenance: create, update (the priority may change the urgent count), status change

  Reads and changes the dashboard doesn't count (renames, tenant details, maintenance assignment, tenant
  logins, role changes) leave the cache alone.
- **If Redis is unavailable:**
  - a failed cache read → the dashboard is calculated from PostgreSQL;
  - a failed cache write → the calculated dashboard is still returned;
  - a failed invalidation → the write still succeeds, and a warning is logged.

  Requests never fail because of Redis; each problem is logged as a warning. The Redis connect timeout is
  1 second, so an unreachable Redis adds at most about a second per Redis call.
- **Cached JSON that can't be read** (corrupt or from an older format) is ignored like a cache miss, and
  is overwritten with a fresh value.
- The Docker Compose Redis runs without persistence (`--save "" --appendonly no`): it is a cache, so after
  a restart it starts empty instead of loading an old dashboard.

## Running tests

```bash
./gradlew clean test
```

Docker must be running: the integration tests start throwaway PostgreSQL and Redis containers with
Testcontainers and call the real HTTP API. The unit tests mock the repositories and the Redis client.
There were 348 tests when this README was written; all of them pass.

## Database migrations

Flyway migrations live in `src/main/resources/db/migration` and run automatically at startup:

| Version | What it does |
|---|---|
| V1 | initial schema: users, properties, rooms, beds, tenants, occupancy history, payments, maintenance |
| V2 | restrict deletes of properties with rooms and rooms with beds |
| V3 | tenant status PENDING / ACTIVE / CHECKED_OUT, positive rent |
| V4 | rent payments: receipt id, one PENDING payment per tenant and month |
| V5 | maintenance issues and TENANT logins |

Never edit a migration that has already run; add a new version instead.

## Known limitations

- **Public sign-up creates MANAGER accounts**, and a MANAGER can see and change all PG data. For a real
  deployment, public registration should be turned off or new accounts should need ADMIN approval.
- **JWTs are not revoked.** Role changes and deleted logins take effect only when the old token expires
  (default 1 hour).
- **Tenants can't change or reset their password.** Staff set it when they create the tenant's login.
- **Maintenance issues need a checked-in tenant.** There are no property-wide issues (for example a broken lift).
- **The dashboard is PG-wide.** There is no per-property dashboard, and payment amounts are all-time totals.
- **The dashboard can be out of date for up to the TTL in two rare cases:** a write happens while a
  dashboard request is calculating (the older numbers can be cached just after the write cleared the key),
  or the cache can't be cleared because Redis is briefly unreachable.
- **No pagination:** list endpoints return all matching rows.
- **No application container yet:** Docker Compose only runs PostgreSQL and Redis; the application runs with Gradle.
- **Route order matters for one route:** `GET /api/tenants/:tenantId/maintenance` (open to tenants) is
  registered in `MainVerticle` before the staff-only `/api/tenants*` guard. Keep that order when adding routes.
