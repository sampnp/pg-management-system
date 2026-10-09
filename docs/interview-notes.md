# Interview notes

Short answers to the questions this project usually raises, based on the actual code. File names are under
`src/main/java/com/pgmanager/`.

## Architecture: why Vert.x instead of Spring Boot?

- I wanted to see and control every layer: routes, middleware, wiring and SQL are all explicit
  (`MainVerticle` wires objects by hand - no annotations, no classpath scanning).
- Vert.x is non-blocking end to end (HTTP server, PostgreSQL client, Redis client), so one process handles many
  concurrent requests with a few event-loop threads.
- Trade-off: more code to write myself (validation, error handling, dependency wiring) and fewer ready-made
  integrations than Spring. For a small, well-defined API that was acceptable - and educational.
- It is still a layered monolith: controller → service → repository, like a typical Spring app.

## Reactive design: what does non-blocking mean here?

- A Vert.x event-loop thread must never wait. Database and Redis calls return a `Future` immediately; the rest of
  the work is chained with `compose`/`map` and runs when the answer arrives.
- Two things are blocking by nature and run on worker threads with `vertx.executeBlocking`: BCrypt and Flyway
  (JDBC, only at startup).
- Vert.x warns ("Thread blocked") when an event loop is busy too long; the tests and the Docker smoke test log none.

## PostgreSQL: why PostgreSQL, and why no ORM?

- Relational data with real rules (a bed holds one tenant, payments belong to tenants, history must survive), so a
  relational database with transactions, row locks, foreign keys, CHECK constraints and partial unique indexes.
- No ORM: the Vert.x reactive client works with SQL directly, and plain SQL keeps the important parts visible -
  `FOR UPDATE` locks, `count(*) FILTER (...)` aggregates, `INSERT ... SELECT`, conditional updates. Every query is
  parameterized (`$1, $2`), so input can't change the SQL. Filters build only the placeholder list, never values.
- Schema changes are Flyway migrations (V1-V7), never edits of old ones.

## Transactions / concurrency: how does check-in prevent two tenants taking the same bed?

`OccupancyService.checkIn` runs in one transaction:

1. `SELECT ... FROM tenants WHERE id = $1 FOR UPDATE` - lock the tenant.
2. The tenant must have no open stay.
3. `SELECT ... FROM beds WHERE id = $1 FOR UPDATE` - lock the bed.
4. The bed must be AVAILABLE.
5. Insert the stay, mark the bed OCCUPIED and the tenant ACTIVE; commit.

If two requests try the same bed at the same moment, the second blocks on the bed's row lock until the first
commits, then sees OCCUPIED and gets 409. If anything fails, everything rolls back. As a safety net, partial unique
indexes allow at most one open stay per bed and per tenant even if the code had a bug. Locks are always taken in
the same order (tenant, then bed), so check-in and check-out can't deadlock.
`ConcurrencyIntegrationTest` sends 10 simultaneous check-ins for one bed: exactly one succeeds.

The Phase 9 review found one real race: adding a bed counted the room's beds and then inserted, as two separate
steps, so two requests could both see "1 of 2" and overfill the room. The fix locks the room row first (same
pattern), and the concurrency test now proves the capacity holds.

## JWT: how does authentication work?

- `POST /api/auth/login` checks the password and returns a JWT (HS256) with the user id, role, token version
  and expiry.
- `JwtAuthHandler` verifies the signature and expiry, then loads the account by id (one primary-key query).
  Missing, switched-off or "older than the last password change" accounts get 401. The role is taken from the
  database, not the token, so role changes apply immediately.
- `RoleHandler` then checks the role for the route (403 if wrong).
- Trade-off: the per-request lookup makes JWT a little stateful, but it gives immediate revocation (disable,
  delete, password change) without a token blacklist.

## BCrypt: why not on the event loop?

BCrypt is slow on purpose (cost 12 is about 0.4-0.5 s here). On an event loop it would freeze every other request
handled by that thread for that time. So hashing and checking run with `vertx.executeBlocking` on worker threads,
and the event loop keeps serving other requests. The cost is not lowered for speed. Unknown emails are checked
against a dummy hash, so "no such user" and "wrong password" take the same time.

## Login rate limiting

Failed logins are counted in Redis per IP + email (hashed key), 5 per 15 minutes, then 429. Successful logins
reset the counter and are never counted, and nothing is locked in PostgreSQL. If Redis is down, login works
without the limit (fail-open), because Redis must not become a hard dependency for logging in.

## Redis: why a cache and not the source of truth?

Redis here holds data that can always be recalculated (dashboards) or that is only protective (login counters).
It runs without persistence. If it loses everything, nothing is lost: the next dashboard is calculated from
PostgreSQL. That keeps one source of truth and lets the app keep working while Redis is down.

## Cache invalidation: when is the dashboard cache cleared?

After every write that changes a number on a dashboard, once the database transaction has committed:
properties/rooms/beds created or deleted, bed status, tenant create/delete/check-in/check-out, payment create and
update, maintenance create/update/status. Only the PG-wide key and the keys of the affected properties are
cleared, by writing `cleared` for 5 seconds. During that time dashboards are calculated fresh but not cached, and new
values are stored with `SET ... NX`. That stops a request that calculated *before* the write from putting old
numbers back. The 60-second TTL is the final safety net.

## Failure handling: what happens when Redis goes down?

- Dashboard read fails → calculate from PostgreSQL. Cache write fails → still return the result.
- Cache clear fails → the write still succeeds; the key is remembered and cleared before the cache is used again.
- Login → no rate limit until Redis is back.
- After one failure Redis is skipped for 5 seconds (`RedisBackoff`), so an outage doesn't add a 1-second timeout to
  every request.
- The app stays "healthy": `/api/health` only reports PostgreSQL.

## Docker: how do the three containers communicate?

Docker Compose puts `app`, `postgres` and `redis` on one network, and the app connects to `postgres:5432` and
`redis:6379` by service name. Only the app is published to the outside (port 8080). PostgreSQL and Redis are
published on `127.0.0.1` only, for local tools. The app waits for PostgreSQL's health check and restarts if
startup fails. The image is multi-stage (JDK to build, JRE to run) and runs as a non-root user without secrets.
`docker compose stop app` triggers a graceful shutdown: running requests finish, then the pool and the Redis
client are closed.

## Security: how are roles enforced?

- Path prefixes are protected once in `MainVerticle` (`/api/properties*` etc. → JWT + ADMIN/MANAGER;
  `/api/admin*` → ADMIN), so new endpoints under them are protected automatically.
- Ownership rules that need the data ("is this the tenant's own issue?") are checked in `MaintenanceService`.
- A tenant's own data is identified by the tenant id from their account, never from the request body.
- `AuthorizationMatrixIntegrationTest` calls every protected endpoint as TENANT (403), MANAGER on admin endpoints
  (403) and without a token (401).
- Other hardening: public registration off by default, placeholder JWT secrets refused in production, security
  headers, CORS off unless one origin is configured, 64 KB body limit, generic 500s, and no secrets or tokens in
  logs.

## Pagination: why deterministic ordering?

With `LIMIT/OFFSET`, if two rows have the same sort value (e.g. the same `created_at`), the database may return
them in any order, so a row could appear on two pages or on none. Every paged query therefore ends its
`ORDER BY` with the unique `id` (`created_at DESC, id DESC`), so the order is total and pages never overlap.
`PaginationApiIntegrationTest` walks the pages and compares them with one big page.

## Trade-offs: what was deliberately not built?

- **No microservices, Kafka, Kubernetes, GraphQL, event sourcing or CQRS.** One small domain fits one process
  and one database. Those tools solve problems this app doesn't have.
- **No ORM**, to keep SQL and locking explicit.
- **No token blacklist or logout endpoint.** The per-request account check already covers disable, delete
  and password change.
- **No distributed locks or Redlock.** Row locks in PostgreSQL protect the data, and the cache only needs
  "cleared" markers and a TTL.
- **No email/password reset**, because there is no email infrastructure.
- **No per-command Redis timeout or full circuit breaker.** A small 5-second back-off was enough for this app.
- **No property-wide maintenance issues and no per-IP-only rate limit.** I kept them out of scope and documented
  them as limitations.
