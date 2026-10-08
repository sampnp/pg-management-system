-- V1 cascaded deletes: deleting a property silently deleted all its rooms and beds.
-- Switch to RESTRICT so a property with rooms (or a room with beds) cannot be deleted by accident.
-- The API turns the resulting foreign-key violation into a 409 Conflict.

ALTER TABLE rooms
    DROP CONSTRAINT rooms_property_id_fkey,
    ADD CONSTRAINT rooms_property_id_fkey
        FOREIGN KEY (property_id) REFERENCES properties (id) ON DELETE RESTRICT;

ALTER TABLE beds
    DROP CONSTRAINT beds_room_id_fkey,
    ADD CONSTRAINT beds_room_id_fkey
        FOREIGN KEY (room_id) REFERENCES rooms (id) ON DELETE RESTRICT;
