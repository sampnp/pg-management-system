package com.pgmanager.repository;

import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;
import java.util.UUID;

/** SQL for the users table. All queries are parameterized ($1, $2...) so user input can never change the SQL. */
public class UserRepository {

    private static final String COLUMNS = "id, name, email, password_hash, role, created_at, tenant_id, active, token_version";

    private final Pool pool;

    public UserRepository(Pool pool) {
        this.pool = pool;
    }

    public Future<Optional<User>> findByEmail(String email) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM users WHERE email = $1")
                .execute(Tuple.of(email))
                .map(rows -> DbUtils.firstRow(rows).map(UserRepository::toUser));
    }

    public Future<Optional<User>> findById(UUID id) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM users WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(UserRepository::toUser));
    }

    public Future<Boolean> adminExists() {
        return pool.query("SELECT EXISTS (SELECT 1 FROM users WHERE role = 'ADMIN') AS admin_exists")
                .execute()
                .map(rows -> rows.iterator().next().getBoolean("admin_exists"));
    }

    /** Creates a staff account (ADMIN or MANAGER). */
    public Future<User> insert(String name, String email, String passwordHash, Role role) {
        return pool.preparedQuery("INSERT INTO users (name, email, password_hash, role) VALUES ($1, $2, $3, $4) RETURNING " + COLUMNS)
                .execute(Tuple.of(name, email, passwordHash, role.name()))
                .map(rows -> toUser(rows.iterator().next()))
                // The UNIQUE constraint is the final guard if two requests register the same email at the same time
                .recover(err -> Future.failedFuture(translateWriteError(err)));
    }

    /** Creates the login of a tenant: role TENANT, linked to that tenant. */
    public Future<User> insertTenantUser(String name, String email, String passwordHash, UUID tenantId) {
        return pool.preparedQuery("INSERT INTO users (name, email, password_hash, role, tenant_id) VALUES ($1, $2, $3, $4, $5) RETURNING " + COLUMNS)
                .execute(Tuple.of(name, email, passwordHash, Role.TENANT.name(), tenantId))
                .map(rows -> toUser(rows.iterator().next()))
                .recover(err -> Future.failedFuture(translateWriteError(err)));
    }

    /** Empty if no user has this id. */
    public Future<Optional<User>> updateRole(UUID id, Role role) {
        return pool.preparedQuery("UPDATE users SET role = $2 WHERE id = $1 RETURNING " + COLUMNS)
                .execute(Tuple.of(id, role.name()))
                .map(rows -> DbUtils.firstRow(rows).map(UserRepository::toUser));
    }

    /** Switches an account on or off. Empty if no user has this id. */
    public Future<Optional<User>> setActive(UUID id, boolean active) {
        return pool.preparedQuery("UPDATE users SET active = $2 WHERE id = $1 RETURNING " + COLUMNS)
                .execute(Tuple.of(id, active))
                .map(rows -> DbUtils.firstRow(rows).map(UserRepository::toUser));
    }

    private static Throwable translateWriteError(Throwable err) {
        if (DbUtils.isUniqueViolation(err)) {
            return "uq_users_tenant".equals(DbUtils.violatedConstraint(err))
                    ? new ConflictException("Tenant already has a login account")
                    : new ConflictException("Email already exists");
        }
        if (DbUtils.isForeignKeyViolation(err)) {
            return new NotFoundException("Tenant not found");
        }
        return err;
    }

    private static User toUser(Row row) {
        return new User(
                row.getUUID("id"),
                row.getString("name"),
                row.getString("email"),
                row.getString("password_hash"),
                Role.valueOf(row.getString("role")),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getUUID("tenant_id"),
                row.getBoolean("active"),
                row.getInteger("token_version"));
    }
}
