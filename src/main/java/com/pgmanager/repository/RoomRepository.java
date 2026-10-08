package com.pgmanager.repository;

import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Room;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the rooms table. */
public class RoomRepository {

    private static final String COLUMNS = "id, property_id, room_number, capacity";
    private static final String DUPLICATE_ROOM = "Room number already exists in this property";

    private final Pool pool;

    public RoomRepository(Pool pool) {
        this.pool = pool;
    }

    public Future<Room> create(UUID propertyId, String roomNumber, int capacity) {
        return pool.preparedQuery("INSERT INTO rooms (property_id, room_number, capacity) VALUES ($1, $2, $3) RETURNING " + COLUMNS)
                .execute(Tuple.of(propertyId, roomNumber, capacity))
                .map(rows -> toRoom(rows.iterator().next()))
                .recover(err -> Future.failedFuture(translateWriteError(err)));
    }

    public Future<List<Room>> findByPropertyId(UUID propertyId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM rooms WHERE property_id = $1 ORDER BY room_number")
                .execute(Tuple.of(propertyId))
                .map(rows -> DbUtils.mapAll(rows, RoomRepository::toRoom));
    }

    public Future<Optional<Room>> findById(UUID id) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM rooms WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(RoomRepository::toRoom));
    }

    /** Returns the updated room, or empty if no room has this id. */
    public Future<Optional<Room>> update(UUID id, String roomNumber, int capacity) {
        return pool.preparedQuery("UPDATE rooms SET room_number = $2, capacity = $3 WHERE id = $1 RETURNING " + COLUMNS)
                .execute(Tuple.of(id, roomNumber, capacity))
                .map(rows -> DbUtils.firstRow(rows).map(RoomRepository::toRoom))
                .recover(err -> Future.failedFuture(translateWriteError(err)));
    }

    /** Returns false if no room has this id. Fails with 409 if beds still belong to it. */
    public Future<Boolean> delete(UUID id) {
        return pool.preparedQuery("DELETE FROM rooms WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.rowCount() > 0)
                .recover(err -> Future.failedFuture(DbUtils.isForeignKeyViolation(err)
                        ? new ConflictException("Room cannot be deleted while it still has beds")
                        : err));
    }

    private static Throwable translateWriteError(Throwable err) {
        if (DbUtils.isUniqueViolation(err)) {
            return new ConflictException(DUPLICATE_ROOM);
        }
        if (DbUtils.isForeignKeyViolation(err)) {
            // The service checks the property first; this covers it being deleted at the same moment
            return new NotFoundException("Property not found");
        }
        return err;
    }

    private static Room toRoom(Row row) {
        return new Room(
                row.getUUID("id"),
                row.getUUID("property_id"),
                row.getString("room_number"),
                row.getInteger("capacity"));
    }
}
