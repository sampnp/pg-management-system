package com.pgmanager.repository;

import com.pgmanager.exception.ConflictException;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import io.vertx.core.Future;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;

/** SQL for the users table. All queries are parameterized ($1, $2...) so user input can never change the SQL. */
public class UserRepository {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String COLUMNS = "id, name, email, password_hash, role, created_at";

    private final Pool pool;

    public UserRepository(Pool pool) {
        this.pool = pool;
    }

    public Future<Optional<User>> findByEmail(String email) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM users WHERE email = $1")
                .execute(Tuple.of(email))
                .map(rows -> firstRow(rows).map(UserRepository::toUser));
    }

    public Future<User> insert(String name, String email, String passwordHash, Role role) {
        return pool.preparedQuery("INSERT INTO users (name, email, password_hash, role) VALUES ($1, $2, $3, $4) RETURNING " + COLUMNS)
                .execute(Tuple.of(name, email, passwordHash, role.name()))
                .map(rows -> toUser(rows.iterator().next()))
                // The UNIQUE constraint is the final guard if two requests register the same email at the same time
                .recover(err -> Future.failedFuture(isUniqueViolation(err)
                        ? new ConflictException("Email already exists")
                        : err));
    }

    private static Optional<Row> firstRow(RowSet<Row> rows) {
        var iterator = rows.iterator();
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }

    private static boolean isUniqueViolation(Throwable err) {
        return err instanceof PgException pgException && UNIQUE_VIOLATION.equals(pgException.getSqlState());
    }

    private static User toUser(Row row) {
        return new User(
                row.getUUID("id"),
                row.getString("name"),
                row.getString("email"),
                row.getString("password_hash"),
                Role.valueOf(row.getString("role")),
                row.getOffsetDateTime("created_at").toInstant());
    }
}
