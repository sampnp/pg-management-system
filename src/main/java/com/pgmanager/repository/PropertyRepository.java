package com.pgmanager.repository;

import com.pgmanager.model.Property;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the properties table. */
public class PropertyRepository {

    private static final String COLUMNS = "id, name, address, city, created_at";

    private final Pool pool;

    public PropertyRepository(Pool pool) {
        this.pool = pool;
    }

    public Future<Property> create(String name, String address, String city) {
        return pool.preparedQuery("INSERT INTO properties (name, address, city) VALUES ($1, $2, $3) RETURNING " + COLUMNS)
                .execute(Tuple.of(name, address, city))
                .map(rows -> toProperty(rows.iterator().next()));
    }

    public Future<List<Property>> findAll() {
        return pool.query("SELECT " + COLUMNS + " FROM properties ORDER BY created_at, id")
                .execute()
                .map(rows -> DbUtils.mapAll(rows, PropertyRepository::toProperty));
    }

    public Future<Optional<Property>> findById(UUID id) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM properties WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(PropertyRepository::toProperty));
    }

    /** Returns the updated property, or empty if no property has this id. */
    public Future<Optional<Property>> update(UUID id, String name, String address, String city) {
        return pool.preparedQuery("UPDATE properties SET name = $2, address = $3, city = $4 WHERE id = $1 RETURNING " + COLUMNS)
                .execute(Tuple.of(id, name, address, city))
                .map(rows -> DbUtils.firstRow(rows).map(PropertyRepository::toProperty));
    }

    /** Returns false if no property has this id. */
    public Future<Boolean> delete(UUID id) {
        return pool.preparedQuery("DELETE FROM properties WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.rowCount() > 0);
    }

    private static Property toProperty(Row row) {
        return new Property(
                row.getUUID("id"),
                row.getString("name"),
                row.getString("address"),
                row.getString("city"),
                row.getOffsetDateTime("created_at").toInstant());
    }
}
