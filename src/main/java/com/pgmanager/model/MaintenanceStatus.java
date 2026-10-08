package com.pgmanager.model;

/**
 * Lifecycle of a maintenance issue. Allowed moves:
 * <pre>
 * OPEN        -> IN_PROGRESS or RESOLVED (a quick fix can skip IN_PROGRESS)
 * IN_PROGRESS -> RESOLVED
 * RESOLVED    -> CLOSED, or back to OPEN if the problem came back (reopen)
 * CLOSED      -> nothing, it is final
 * </pre>
 */
public enum MaintenanceStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED;

    public boolean canChangeTo(MaintenanceStatus next) {
        return switch (this) {
            case OPEN -> next == IN_PROGRESS || next == RESOLVED;
            case IN_PROGRESS -> next == RESOLVED;
            case RESOLVED -> next == CLOSED || next == OPEN;
            case CLOSED -> false;
        };
    }
}
