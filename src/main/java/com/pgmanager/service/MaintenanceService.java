package com.pgmanager.service;

import com.pgmanager.dto.AssignRequest;
import com.pgmanager.dto.MaintenanceRequest;
import com.pgmanager.dto.MaintenanceStatusRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.ForbiddenException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.MaintenanceCategory;
import com.pgmanager.model.MaintenanceIssue;
import com.pgmanager.model.MaintenancePriority;
import com.pgmanager.model.MaintenanceStatus;
import com.pgmanager.model.Role;
import com.pgmanager.model.Tenant;
import com.pgmanager.repository.MaintenanceRepository;
import com.pgmanager.repository.TenantRepository;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.AuthUser;
import io.vertx.core.Future;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintenance issues: tenants report problems in their room, staff assign and resolve them.
 *
 * Access rules: rules that only depend on the caller's role (e.g. "only staff can assign") are enforced by
 * RoleHandler on the route. Rules that depend on the issue itself (e.g. "a tenant can only see their own
 * issues") are checked here, because the service is the first place that knows whose issue it is.
 */
public class MaintenanceService {

    static final String ISSUE_NOT_FOUND = "Maintenance issue not found";
    static final String NOT_YOUR_ISSUE = "You can only access your own maintenance issues";
    static final String CHANGED_CONCURRENTLY = "The issue was changed by someone else at the same time, please try again";
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final MaintenanceRepository maintenanceRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;

    public MaintenanceService(MaintenanceRepository maintenanceRepository, TenantRepository tenantRepository,
                              UserRepository userRepository) {
        this.maintenanceRepository = maintenanceRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    /**
     * Reports an issue at the tenant's current bed. A tenant always reports for themselves (the tenant comes
     * from their token); staff must say which tenant. The tenant has to be checked in.
     */
    public Future<MaintenanceIssue> create(AuthUser caller, MaintenanceRequest request) {
        return Future.succeededFuture(request)
                .map(MaintenanceService::validate)
                .compose(details -> {
                    UUID tenantId = reportingTenant(caller, request.tenantId());
                    MaintenancePriority priority = details.priority() != null ? details.priority() : MaintenancePriority.MEDIUM;
                    return requireTenant(tenantId)
                            .compose(tenant -> maintenanceRepository.create(tenantId, details.title(), details.description(),
                                    details.category(), priority))
                            .map(created -> created.orElseThrow(() ->
                                    new ConflictException("Tenant must be checked in to a bed to report an issue")));
                });
    }

    public Future<MaintenanceIssue> findById(AuthUser caller, UUID id) {
        return findIssue(id)
                .map(issue -> {
                    requireAccess(caller, issue.tenantId());
                    return issue;
                });
    }

    /** Staff only (checked on the route). All filters are optional and combined with AND. Newest first. */
    public Future<List<MaintenanceIssue>> list(String tenantId, String status, String priority, String category) {
        // An invalid filter throws inside compose(), which turns it into a failed Future (-> 400)
        return Future.succeededFuture()
                .compose(v -> maintenanceRepository.find(
                        isBlank(tenantId) ? null : Validation.requireUuid(tenantId, "tenantId"),
                        isBlank(status) ? null : parseStatus(status),
                        isBlank(priority) ? null : parsePriority(priority),
                        isBlank(category) ? null : parseCategory(category)));
    }

    /**
     * Edits title, description, category and priority. Staff can edit any issue that is not CLOSED.
     * A tenant can edit only their own issue, and only while it is OPEN (before staff start working on it).
     * A missing priority keeps the current one. The tenant of an issue can't be changed.
     */
    public Future<MaintenanceIssue> update(AuthUser caller, UUID id, MaintenanceRequest request) {
        return Future.succeededFuture(request)
                .map(MaintenanceService::validate)
                .compose(details -> findIssue(id)
                        .compose(existing -> {
                            requireAccess(caller, existing.tenantId());
                            if (!isBlank(request.tenantId())
                                    && !Validation.requireUuid(request.tenantId(), "tenantId").equals(existing.tenantId())) {
                                throw new BadRequestException("tenantId of an issue cannot be changed");
                            }
                            requireNotClosed(existing);
                            if (!isStaff(caller) && existing.status() != MaintenanceStatus.OPEN) {
                                throw new ConflictException("An issue can only be edited by the tenant while it is OPEN");
                            }
                            MaintenancePriority priority = details.priority() != null ? details.priority() : existing.priority();
                            return maintenanceRepository.update(id, details.title(), details.description(), details.category(),
                                    priority, existing.status());
                        }))
                .map(MaintenanceService::orConcurrentChange);
    }

    /** Staff only (checked on the route). The assignee must be an existing ADMIN or MANAGER. */
    public Future<MaintenanceIssue> assign(UUID id, AssignRequest request) {
        return Future.succeededFuture(request)
                .map(r -> Validation.requireUuid(Validation.requireBody(r).assignedTo(), "assignedTo"))
                .compose(assigneeId -> findIssue(id)
                        .compose(existing -> {
                            requireNotClosed(existing);
                            return userRepository.findById(assigneeId)
                                    .map(user -> user.orElseThrow(() -> new NotFoundException("Assigned user not found")))
                                    .compose(user -> {
                                        if (user.role() != Role.ADMIN && user.role() != Role.MANAGER) {
                                            throw new BadRequestException("Issues can only be assigned to an ADMIN or MANAGER");
                                        }
                                        return maintenanceRepository.assign(id, assigneeId, existing.status());
                                    });
                        }))
                .map(MaintenanceService::orConcurrentChange);
    }

    /** Staff only (checked on the route). Only the moves allowed by MaintenanceStatus.canChangeTo are accepted. */
    public Future<MaintenanceIssue> changeStatus(UUID id, MaintenanceStatusRequest request) {
        return Future.succeededFuture(request)
                .map(r -> {
                    String status = Validation.requireBody(r).status();
                    if (isBlank(status)) {
                        throw new BadRequestException("status is required");
                    }
                    return parseStatus(status);
                })
                .compose(newStatus -> findIssue(id)
                        .compose(existing -> {
                            if (existing.status() == newStatus) {
                                throw new ConflictException("Issue is already " + newStatus);
                            }
                            if (!existing.status().canChangeTo(newStatus)) {
                                throw new ConflictException("Cannot change status from " + existing.status() + " to " + newStatus);
                            }
                            return maintenanceRepository.changeStatus(id, newStatus, existing.status());
                        }))
                .map(MaintenanceService::orConcurrentChange);
    }

    /** All issues of a tenant, newest first, kept after check-out. Staff can see any tenant, a tenant only themselves. */
    public Future<List<MaintenanceIssue>> tenantHistory(AuthUser caller, UUID tenantId) {
        return Future.succeededFuture(tenantId)
                .map(id -> {
                    // Checked before looking the tenant up, so a tenant can't even find out which other tenant ids exist
                    requireAccess(caller, id);
                    return id;
                })
                .compose(this::requireTenant)
                .compose(tenant -> maintenanceRepository.find(tenantId, null, null, null));
    }

    /** The tenant an issue is reported for. A tenant can only report for themselves - the body can't override that. */
    private static UUID reportingTenant(AuthUser caller, String requestedTenantId) {
        if (isStaff(caller)) {
            return Validation.requireUuid(requestedTenantId, "tenantId");
        }
        if (!isBlank(requestedTenantId) && !Validation.requireUuid(requestedTenantId, "tenantId").equals(caller.tenantId())) {
            throw new ForbiddenException("Tenants can only report issues for themselves");
        }
        return requireOwnTenant(caller);
    }

    private static void requireAccess(AuthUser caller, UUID tenantId) {
        if (!isStaff(caller) && !tenantId.equals(requireOwnTenant(caller))) {
            throw new ForbiddenException(NOT_YOUR_ISSUE);
        }
    }

    /** The tenant linked to a TENANT user. Every TENANT token has one; anything else is refused. */
    private static UUID requireOwnTenant(AuthUser caller) {
        if (caller == null || caller.role() != Role.TENANT || caller.tenantId() == null) {
            throw new ForbiddenException("Insufficient permissions");
        }
        return caller.tenantId();
    }

    private static boolean isStaff(AuthUser caller) {
        return caller != null && (caller.role() == Role.ADMIN || caller.role() == Role.MANAGER);
    }

    private static void requireNotClosed(MaintenanceIssue issue) {
        if (issue.status() == MaintenanceStatus.CLOSED) {
            throw new ConflictException("A CLOSED issue cannot be changed");
        }
    }

    /** The update methods return empty when the issue's status changed after we checked it. */
    private static MaintenanceIssue orConcurrentChange(Optional<MaintenanceIssue> updated) {
        return updated.orElseThrow(() -> new ConflictException(CHANGED_CONCURRENTLY));
    }

    private Future<MaintenanceIssue> findIssue(UUID id) {
        return maintenanceRepository.findById(id)
                .map(issue -> issue.orElseThrow(() -> new NotFoundException(ISSUE_NOT_FOUND)));
    }

    private Future<Tenant> requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenant -> tenant.orElseThrow(() -> new NotFoundException(TenantService.TENANT_NOT_FOUND)));
    }

    /** Validated and normalized issue details. priority is null when the request didn't send one. */
    private record IssueDetails(String title, String description, MaintenanceCategory category, MaintenancePriority priority) {
    }

    private static IssueDetails validate(MaintenanceRequest request) {
        Validation.requireBody(request);
        String title = Validation.requireText(request.title(), "title", MAX_TITLE_LENGTH);
        String description = Validation.requireText(request.description(), "description", MAX_DESCRIPTION_LENGTH);
        if (isBlank(request.category())) {
            throw new BadRequestException("category is required");
        }
        MaintenanceCategory category = parseCategory(request.category());
        MaintenancePriority priority = isBlank(request.priority()) ? null : parsePriority(request.priority());
        return new IssueDetails(title, description, category, priority);
    }

    private static MaintenanceCategory parseCategory(String value) {
        try {
            return MaintenanceCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("category must be PLUMBING, ELECTRICAL, CLEANING, FURNITURE, APPLIANCE, INTERNET or OTHER");
        }
    }

    private static MaintenancePriority parsePriority(String value) {
        try {
            return MaintenancePriority.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("priority must be LOW, MEDIUM, HIGH or URGENT");
        }
    }

    private static MaintenanceStatus parseStatus(String value) {
        try {
            return MaintenanceStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("status must be OPEN, IN_PROGRESS, RESOLVED or CLOSED");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
