package com.pgmanager.repository;

import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Bed;
import com.pgmanager.model.BedStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the beds table. */
public class BedRepository {

    private static final String COLUMNS = "id, room_id, bed_number, status";

    private final Pool pool;

    public BedRepository(Pool pool) {
        this.pool = pool;
    }

    /** New beds get the column default status, AVAILABLE. */
    /** Runs in the caller's transaction (after the room is locked, see BedService.create). */
    public Future<Bed> create(SqlClient client, UUID roomId, String bedNumber) {
        return client.preparedQuery("INSERT INTO beds (room_id, bed_number) VALUES ($1, $2) RETURNING " + COLUMNS)
                .execute(Tuple.of(roomId, bedNumber))
                .map(rows -> toBed(rows.iterator().next()))
                .recover(err -> Future.failedFuture(translateWriteError(err)));
    }

    public Future<List<Bed>> findByRoomId(UUID roomId) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM beds WHERE room_id = $1 ORDER BY bed_number")
                .execute(Tuple.of(roomId))
                .map(rows -> DbUtils.mapAll(rows, BedRepository::toBed));
    }

    public Future<Optional<Bed>> findById(UUID id) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM beds WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(BedRepository::toBed));
    }

    public Future<Integer> countByRoomId(SqlClient client, UUID roomId) {
        return client.preparedQuery("SELECT COUNT(*) AS bed_count FROM beds WHERE room_id = $1")
                .execute(Tuple.of(roomId))
                .map(rows -> rows.iterator().next().getInteger("bed_count"));
    }

    /** Returns the updated bed, or empty if no bed has this id. */
    public Future<Optional<Bed>> updateBedNumber(UUID id, String bedNumber) {
        return pool.preparedQuery("UPDATE beds SET bed_number = $2 WHERE id = $1 RETURNING " + COLUMNS)
                .execute(Tuple.of(id, bedNumber))
                .map(rows -> DbUtils.firstRow(rows).map(BedRepository::toBed))
                .recover(err -> Future.failedFuture(translateWriteError(err)));
    }

    /**
     * Same as findById, but locks the bed row until the transaction ends (FOR UPDATE).
     * If two check-ins race for the same bed, the second one waits here and then sees OCCUPIED.
     */
    public Future<Optional<Bed>> findByIdForUpdate(SqlClient client, UUID id) {
        return client.preparedQuery("SELECT " + COLUMNS + " FROM beds WHERE id = $1 FOR UPDATE")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(BedRepository::toBed));
    }

    /** Runs on the given transaction connection: status only changes together with occupancy. Empty if no bed has this id. */
    public Future<Optional<Bed>> updateStatus(SqlClient client, UUID id, BedStatus status) {
        return client.preparedQuery("UPDATE beds SET status = $2 WHERE id = $1 RETURNING " + COLUMNS)
                .execute(Tuple.of(id, status.name()))
                .map(rows -> DbUtils.firstRow(rows).map(BedRepository::toBed));
    }

    /** Returns false if no bed has this id. Fails with 409 if occupancy history references it. */
    public Future<Boolean> delete(UUID id) {
        return pool.preparedQuery("DELETE FROM beds WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.rowCount() > 0)
                .recover(err -> Future.failedFuture(DbUtils.isForeignKeyViolation(err)
                        ? new ConflictException("Bed cannot be deleted because it has occupancy history")
                        : err));
    }

    private static Throwable translateWriteError(Throwable err) {
        if (DbUtils.isUniqueViolation(err)) {
            return new ConflictException("Bed number already exists in this room");
        }
        if (DbUtils.isForeignKeyViolation(err)) {
            return new NotFoundException("Room not found");
        }
        return err;
    }

    private static Bed toBed(Row row) {
        return new Bed(
                row.getUUID("id"),
                row.getUUID("room_id"),
                row.getString("bed_number"),
                BedStatus.valueOf(row.getString("status")));
    }
}
