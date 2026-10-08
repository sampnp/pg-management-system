package com.pgmanager.repository;

import com.pgmanager.model.Bed;
import com.pgmanager.model.BedStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
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
    public Future<Bed> create(UUID roomId, String bedNumber) {
        return pool.preparedQuery("INSERT INTO beds (room_id, bed_number) VALUES ($1, $2) RETURNING " + COLUMNS)
                .execute(Tuple.of(roomId, bedNumber))
                .map(rows -> toBed(rows.iterator().next()));
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

    /** Returns the updated bed, or empty if no bed has this id. */
    public Future<Optional<Bed>> updateBedNumber(UUID id, String bedNumber) {
        return pool.preparedQuery("UPDATE beds SET bed_number = $2 WHERE id = $1 RETURNING " + COLUMNS)
                .execute(Tuple.of(id, bedNumber))
                .map(rows -> DbUtils.firstRow(rows).map(BedRepository::toBed));
    }

    /** Returns the updated bed, or empty if no bed has this id. */
    public Future<Optional<Bed>> updateStatus(UUID id, BedStatus status) {
        return pool.preparedQuery("UPDATE beds SET status = $2 WHERE id = $1 RETURNING " + COLUMNS)
                .execute(Tuple.of(id, status.name()))
                .map(rows -> DbUtils.firstRow(rows).map(BedRepository::toBed));
    }

    /** Returns false if no bed has this id. */
    public Future<Boolean> delete(UUID id) {
        return pool.preparedQuery("DELETE FROM beds WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.rowCount() > 0);
    }

    private static Bed toBed(Row row) {
        return new Bed(
                row.getUUID("id"),
                row.getUUID("room_id"),
                row.getString("bed_number"),
                BedStatus.valueOf(row.getString("status")));
    }
}
