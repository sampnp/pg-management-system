package com.pgmanager.service;

import com.pgmanager.dto.AssignRequest;
import com.pgmanager.dto.MaintenanceRequest;
import com.pgmanager.dto.MaintenanceStatusRequest;
import com.pgmanager.dto.Page;
import com.pgmanager.dto.PageRequest;
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
import com.pgmanager.model.TenantStatus;
import com.pgmanager.model.User;
import com.pgmanager.repository.DashboardCache;
import com.pgmanager.repository.MaintenanceRepository;
import com.pgmanager.repository.TenantRepository;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.AuthUser;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** MaintenanceService with mocked repositories: validation, access rules and status transitions. */
class MaintenanceServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID otherTenantId = UUID.randomUUID();
    private final AuthUser tenantUser = new AuthUser(UUID.randomUUID(), "ravi@example.com", Role.TENANT, tenantId);
    private final AuthUser manager = new AuthUser(UUID.randomUUID(), "manager@example.com", Role.MANAGER);
    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "admin@example.com", Role.ADMIN);

    private MaintenanceRepository maintenanceRepository;
    private TenantRepository tenantRepository;
    private UserRepository userRepository;
    private MaintenanceService maintenanceService;

    private DashboardCache dashboardCache;

    @BeforeEach
    void setUp() {
        maintenanceRepository = mock(MaintenanceRepository.class);
        tenantRepository = mock(TenantRepository.class);
        userRepository = mock(UserRepository.class);
        dashboardCache = mock(DashboardCache.class);
        when(dashboardCache.invalidate()).thenReturn(Future.succeededFuture());
        maintenanceService = new MaintenanceService(maintenanceRepository, tenantRepository, userRepository, dashboardCache);
    }

    // ---------- create ----------

    @Test
    void tenantReportsForThemselvesWithNormalizedValues() throws Exception {
        tenantExists(tenantId);
        MaintenanceIssue created = issue(tenantId, MaintenanceStatus.OPEN);
        when(maintenanceRepository.create(tenantId, "Tap leaking", "Since morning", MaintenanceCategory.PLUMBING, MaintenancePriority.HIGH))
                .thenReturn(Future.succeededFuture(Optional.of(created)));

        MaintenanceIssue result = await(maintenanceService.create(tenantUser,
                request(null, "  Tap leaking ", " Since morning ", "plumbing", " high ")));

        assertEquals(created, result);
        verify(dashboardCache).invalidate();
    }

    @Test
    void priorityDefaultsToMedium() throws Exception {
        tenantExists(tenantId);
        when(maintenanceRepository.create(tenantId, "Fan noise", "Ceiling fan is noisy", MaintenanceCategory.ELECTRICAL, MaintenancePriority.MEDIUM))
                .thenReturn(Future.succeededFuture(Optional.of(issue(tenantId, MaintenanceStatus.OPEN))));

        await(maintenanceService.create(tenantUser, request(null, "Fan noise", "Ceiling fan is noisy", "ELECTRICAL", null)));

        verify(maintenanceRepository).create(tenantId, "Fan noise", "Ceiling fan is noisy", MaintenanceCategory.ELECTRICAL, MaintenancePriority.MEDIUM);
    }

    @Test
    void tenantMaySendTheirOwnTenantId() throws Exception {
        tenantExists(tenantId);
        when(maintenanceRepository.create(any(), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(Optional.of(issue(tenantId, MaintenanceStatus.OPEN))));

        await(maintenanceService.create(tenantUser, request(tenantId.toString(), "Wifi down", "No internet", "INTERNET", "LOW")));

        verify(maintenanceRepository).create(tenantId, "Wifi down", "No internet", MaintenanceCategory.INTERNET, MaintenancePriority.LOW);
    }

    @Test
    void tenantCannotReportForAnotherTenant() throws Exception {
        Throwable error = awaitFailure(maintenanceService.create(tenantUser,
                request(otherTenantId.toString(), "Tap leaking", "Since morning", "PLUMBING", "HIGH")));

        assertInstanceOf(ForbiddenException.class, error);
        assertEquals("Tenants can only report issues for themselves", error.getMessage());
        verifyNoInteractions(maintenanceRepository, tenantRepository);
    }

    @Test
    void staffReportForTheTenantInTheBody() throws Exception {
        tenantExists(otherTenantId);
        when(maintenanceRepository.create(any(), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(Optional.of(issue(otherTenantId, MaintenanceStatus.OPEN))));

        await(maintenanceService.create(manager, request(otherTenantId.toString(), "Broken chair", "Leg is broken", "FURNITURE", "LOW")));

        verify(maintenanceRepository).create(otherTenantId, "Broken chair", "Leg is broken", MaintenanceCategory.FURNITURE, MaintenancePriority.LOW);
    }

    @Test
    void staffMustSayWhichTenant() throws Exception {
        Throwable error = awaitFailure(maintenanceService.create(manager, request(null, "Broken chair", "Leg is broken", "FURNITURE", null)));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals("tenantId is required", error.getMessage());
        verifyNoInteractions(maintenanceRepository);
    }

    @Test
    void unknownTenantFailsWith404() throws Exception {
        when(tenantRepository.findById(otherTenantId)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(maintenanceService.create(admin, request(otherTenantId.toString(), "Leak", "Leak", "PLUMBING", null)));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Tenant not found", error.getMessage());
        verify(maintenanceRepository, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void tenantWhoIsNotCheckedInFailsWith409() throws Exception {
        tenantExists(tenantId);
        when(maintenanceRepository.create(any(), any(), any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(maintenanceService.create(tenantUser, request(null, "Leak", "Leak", "PLUMBING", null)));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("Tenant must be checked in to a bed to report an issue", error.getMessage());
    }

    static Stream<Arguments> invalidIssues() {
        String tenant = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(request(tenant, "  ", "Leak", "PLUMBING", null), "title is required"),
                Arguments.of(request(tenant, "x".repeat(201), "Leak", "PLUMBING", null), "title must be at most 200 characters"),
                Arguments.of(request(tenant, "Leak", null, "PLUMBING", null), "description is required"),
                Arguments.of(request(tenant, "Leak", " ", "PLUMBING", null), "description is required"),
                Arguments.of(request(tenant, "Leak", "x".repeat(2001), "PLUMBING", null), "description must be at most 2000 characters"),
                Arguments.of(request(tenant, "Leak", "Leak", null, null), "category is required"),
                Arguments.of(request(tenant, "Leak", "Leak", "GARDEN", null),
                        "category must be PLUMBING, ELECTRICAL, CLEANING, FURNITURE, APPLIANCE, INTERNET or OTHER"),
                Arguments.of(request(tenant, "Leak", "Leak", "PLUMBING", "CRITICAL"), "priority must be LOW, MEDIUM, HIGH or URGENT"),
                Arguments.of(request("not-a-uuid", "Leak", "Leak", "PLUMBING", null), "tenantId must be a valid UUID"));
    }

    @ParameterizedTest
    @MethodSource("invalidIssues")
    void invalidIssueFailsWith400WithoutTouchingTheDatabase(MaintenanceRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(maintenanceService.create(manager, request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(maintenanceRepository, tenantRepository);
    }

    // ---------- view ----------

    @Test
    void tenantCanViewTheirOwnIssue() throws Exception {
        MaintenanceIssue own = existing(tenantId, MaintenanceStatus.OPEN);

        assertEquals(own, await(maintenanceService.findById(tenantUser, own.id())));
    }

    @Test
    void tenantCannotViewAnotherTenantsIssue() throws Exception {
        MaintenanceIssue other = existing(otherTenantId, MaintenanceStatus.OPEN);

        Throwable error = awaitFailure(maintenanceService.findById(tenantUser, other.id()));

        assertInstanceOf(ForbiddenException.class, error);
        assertEquals("You can only access your own maintenance issues", error.getMessage());
    }

    @Test
    void staffCanViewAnyIssue() throws Exception {
        MaintenanceIssue other = existing(otherTenantId, MaintenanceStatus.OPEN);

        assertEquals(other, await(maintenanceService.findById(manager, other.id())));
        assertEquals(other, await(maintenanceService.findById(admin, other.id())));
    }

    @Test
    void missingIssueFailsWith404() throws Exception {
        UUID id = UUID.randomUUID();
        when(maintenanceRepository.findById(id)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(maintenanceService.findById(manager, id));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Maintenance issue not found", error.getMessage());
    }

    @Test
    void listParsesAndCombinesAllFilters() throws Exception {
        when(maintenanceRepository.findPage(any(), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(Page.of(List.of(), new PageRequest(1, 10), 0)));

        await(maintenanceService.list(tenantId.toString(), "open", "high", "plumbing", "1", "10"));

        verify(maintenanceRepository).findPage(tenantId, MaintenanceStatus.OPEN, MaintenancePriority.HIGH,
                MaintenanceCategory.PLUMBING, new PageRequest(1, 10));
    }

    @Test
    void listWithoutFiltersReturnsEverything() throws Exception {
        when(maintenanceRepository.findPage(null, null, null, null, PageRequest.FIRST))
                .thenReturn(Future.succeededFuture(Page.of(List.of(), PageRequest.FIRST, 0)));

        await(maintenanceService.list(null, " ", null, "", null, null));

        verify(maintenanceRepository).findPage(null, null, null, null, PageRequest.FIRST);
    }

    static Stream<Arguments> invalidFilters() {
        return Stream.of(
                Arguments.of("bad", null, null, null, "tenantId must be a valid UUID"),
                Arguments.of(null, "DONE", null, null, "status must be OPEN, IN_PROGRESS, RESOLVED or CLOSED"),
                Arguments.of(null, null, "CRITICAL", null, "priority must be LOW, MEDIUM, HIGH or URGENT"),
                Arguments.of(null, null, null, "GARDEN", "category must be PLUMBING, ELECTRICAL, CLEANING, FURNITURE, APPLIANCE, INTERNET or OTHER"),
                Arguments.of(null, null, null, null, "page must be a number of 0 or more"));
    }

    @ParameterizedTest
    @MethodSource("invalidFilters")
    void invalidFilterFailsWith400(String tenant, String status, String priority, String category, String expectedMessage) throws Exception {
        // The last case has valid filters but page=-1
        String page = tenant == null && status == null && priority == null && category == null ? "-1" : null;
        Throwable error = awaitFailure(maintenanceService.list(tenant, status, priority, category, page, null));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(maintenanceRepository);
    }

    // ---------- update ----------

    @Test
    void tenantCanEditTheirOpenIssueAndMissingPriorityKeepsTheOldOne() throws Exception {
        MaintenanceIssue own = existing(tenantId, MaintenanceStatus.OPEN);   // priority HIGH
        when(maintenanceRepository.update(own.id(), "Tap leaking badly", "Water everywhere", MaintenanceCategory.PLUMBING,
                MaintenancePriority.HIGH, MaintenanceStatus.OPEN)).thenReturn(Future.succeededFuture(Optional.of(own)));

        await(maintenanceService.update(tenantUser, own.id(), request(null, "Tap leaking badly", "Water everywhere", "PLUMBING", null)));

        verify(maintenanceRepository).update(own.id(), "Tap leaking badly", "Water everywhere", MaintenanceCategory.PLUMBING,
                MaintenancePriority.HIGH, MaintenanceStatus.OPEN);
    }

    @Test
    void tenantCannotEditOnceWorkHasStarted() throws Exception {
        MaintenanceIssue own = existing(tenantId, MaintenanceStatus.IN_PROGRESS);

        Throwable error = awaitFailure(maintenanceService.update(tenantUser, own.id(), request(null, "Leak", "Leak", "PLUMBING", null)));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("An issue can only be edited by the tenant while it is OPEN", error.getMessage());
        verify(maintenanceRepository, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    void tenantCannotEditAnotherTenantsIssue() throws Exception {
        MaintenanceIssue other = existing(otherTenantId, MaintenanceStatus.OPEN);

        Throwable error = awaitFailure(maintenanceService.update(tenantUser, other.id(), request(null, "Leak", "Leak", "PLUMBING", null)));

        assertInstanceOf(ForbiddenException.class, error);
        verify(maintenanceRepository, never()).update(any(), any(), any(), any(), any(), any());
    }

    @Test
    void staffCanEditAnIssueInProgress() throws Exception {
        MaintenanceIssue issue = existing(tenantId, MaintenanceStatus.IN_PROGRESS);
        when(maintenanceRepository.update(issue.id(), "Leak", "Leak", MaintenanceCategory.PLUMBING, MaintenancePriority.URGENT,
                MaintenanceStatus.IN_PROGRESS)).thenReturn(Future.succeededFuture(Optional.of(issue)));

        assertEquals(issue, await(maintenanceService.update(manager, issue.id(), request(null, "Leak", "Leak", "PLUMBING", "URGENT"))));
    }

    @Test
    void closedIssueCannotBeEdited() throws Exception {
        MaintenanceIssue closed = existing(tenantId, MaintenanceStatus.CLOSED);

        Throwable error = awaitFailure(maintenanceService.update(admin, closed.id(), request(null, "Leak", "Leak", "PLUMBING", null)));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("A CLOSED issue cannot be changed", error.getMessage());
    }

    @Test
    void tenantOfAnIssueCannotBeChanged() throws Exception {
        MaintenanceIssue issue = existing(tenantId, MaintenanceStatus.OPEN);

        Throwable error = awaitFailure(maintenanceService.update(manager, issue.id(),
                request(otherTenantId.toString(), "Leak", "Leak", "PLUMBING", null)));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals("tenantId of an issue cannot be changed", error.getMessage());
    }

    @Test
    void updateThatLosesARaceFailsWith409() throws Exception {
        MaintenanceIssue issue = existing(tenantId, MaintenanceStatus.OPEN);
        when(maintenanceRepository.update(any(), any(), any(), any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(maintenanceService.update(manager, issue.id(), request(null, "Leak", "Leak", "PLUMBING", null)));

        assertInstanceOf(ConflictException.class, error);
        assertEquals(MaintenanceService.CHANGED_CONCURRENTLY, error.getMessage());
    }

    // ---------- assign ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "MANAGER"})
    void issueCanBeAssignedToStaff(Role role) throws Exception {
        MaintenanceIssue issue = existing(tenantId, MaintenanceStatus.OPEN);
        User staff = user(role, null);
        when(userRepository.findById(staff.id())).thenReturn(Future.succeededFuture(Optional.of(staff)));
        when(maintenanceRepository.assign(issue.id(), staff.id(), MaintenanceStatus.OPEN)).thenReturn(Future.succeededFuture(Optional.of(issue)));

        await(maintenanceService.assign(issue.id(), new AssignRequest(staff.id().toString())));

        verify(maintenanceRepository).assign(issue.id(), staff.id(), MaintenanceStatus.OPEN);
        // The dashboard does not count assignments
        verify(dashboardCache, never()).invalidate();
    }

    @Test
    void issueCannotBeAssignedToATenantAccount() throws Exception {
        MaintenanceIssue issue = existing(tenantId, MaintenanceStatus.OPEN);
        User tenantAccount = user(Role.TENANT, tenantId);
        when(userRepository.findById(tenantAccount.id())).thenReturn(Future.succeededFuture(Optional.of(tenantAccount)));

        Throwable error = awaitFailure(maintenanceService.assign(issue.id(), new AssignRequest(tenantAccount.id().toString())));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals("Issues can only be assigned to an ADMIN or MANAGER", error.getMessage());
        verify(maintenanceRepository, never()).assign(any(), any(), any());
    }

    @Test
    void assigningToUnknownUserFailsWith404() throws Exception {
        MaintenanceIssue issue = existing(tenantId, MaintenanceStatus.OPEN);
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(maintenanceService.assign(issue.id(), new AssignRequest(unknown.toString())));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Assigned user not found", error.getMessage());
    }

    static Stream<Arguments> invalidAssignments() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(new AssignRequest(null), "assignedTo is required"),
                Arguments.of(new AssignRequest("not-a-uuid"), "assignedTo must be a valid UUID"));
    }

    @ParameterizedTest
    @MethodSource("invalidAssignments")
    void invalidAssignmentFailsWith400(AssignRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(maintenanceService.assign(UUID.randomUUID(), request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(maintenanceRepository, userRepository);
    }

    @Test
    void closedIssueCannotBeAssigned() throws Exception {
        MaintenanceIssue closed = existing(tenantId, MaintenanceStatus.CLOSED);

        Throwable error = awaitFailure(maintenanceService.assign(closed.id(), new AssignRequest(UUID.randomUUID().toString())));

        assertInstanceOf(ConflictException.class, error);
        verifyNoInteractions(userRepository);
    }

    // ---------- status ----------

    static Stream<Arguments> allowedMoves() {
        return Stream.of(
                Arguments.of(MaintenanceStatus.OPEN, MaintenanceStatus.IN_PROGRESS),
                Arguments.of(MaintenanceStatus.OPEN, MaintenanceStatus.RESOLVED),
                Arguments.of(MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.RESOLVED),
                Arguments.of(MaintenanceStatus.RESOLVED, MaintenanceStatus.CLOSED),
                Arguments.of(MaintenanceStatus.RESOLVED, MaintenanceStatus.OPEN));
    }

    @ParameterizedTest
    @MethodSource("allowedMoves")
    void allowedStatusChangeIsSaved(MaintenanceStatus from, MaintenanceStatus to) throws Exception {
        MaintenanceIssue issue = existing(tenantId, from);
        when(maintenanceRepository.changeStatus(issue.id(), to, from)).thenReturn(Future.succeededFuture(Optional.of(issue)));

        await(maintenanceService.changeStatus(issue.id(), new MaintenanceStatusRequest(to.name().toLowerCase())));

        verify(maintenanceRepository).changeStatus(issue.id(), to, from);
        verify(dashboardCache).invalidate();
    }

    static Stream<Arguments> forbiddenMoves() {
        return Stream.of(
                Arguments.of(MaintenanceStatus.OPEN, MaintenanceStatus.CLOSED),
                Arguments.of(MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.OPEN),
                Arguments.of(MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.CLOSED),
                Arguments.of(MaintenanceStatus.RESOLVED, MaintenanceStatus.IN_PROGRESS),
                Arguments.of(MaintenanceStatus.CLOSED, MaintenanceStatus.OPEN),
                Arguments.of(MaintenanceStatus.CLOSED, MaintenanceStatus.RESOLVED));
    }

    @ParameterizedTest
    @MethodSource("forbiddenMoves")
    void forbiddenStatusChangeFailsWith409(MaintenanceStatus from, MaintenanceStatus to) throws Exception {
        MaintenanceIssue issue = existing(tenantId, from);

        Throwable error = awaitFailure(maintenanceService.changeStatus(issue.id(), new MaintenanceStatusRequest(to.name())));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("Cannot change status from " + from + " to " + to, error.getMessage());
        verify(maintenanceRepository, never()).changeStatus(any(), any(), any());
        verify(dashboardCache, never()).invalidate();
    }

    @Test
    void changingToTheCurrentStatusFailsWith409() throws Exception {
        MaintenanceIssue issue = existing(tenantId, MaintenanceStatus.OPEN);

        Throwable error = awaitFailure(maintenanceService.changeStatus(issue.id(), new MaintenanceStatusRequest("OPEN")));

        assertInstanceOf(ConflictException.class, error);
        assertEquals("Issue is already OPEN", error.getMessage());
    }

    static Stream<Arguments> invalidStatusRequests() {
        return Stream.of(
                Arguments.of(null, "Request body is required"),
                Arguments.of(new MaintenanceStatusRequest(" "), "status is required"),
                Arguments.of(new MaintenanceStatusRequest("DONE"), "status must be OPEN, IN_PROGRESS, RESOLVED or CLOSED"));
    }

    @ParameterizedTest
    @MethodSource("invalidStatusRequests")
    void invalidStatusFailsWith400(MaintenanceStatusRequest request, String expectedMessage) throws Exception {
        Throwable error = awaitFailure(maintenanceService.changeStatus(UUID.randomUUID(), request));

        assertInstanceOf(BadRequestException.class, error);
        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(maintenanceRepository);
    }

    @Test
    void statusChangeThatLosesARaceFailsWith409() throws Exception {
        MaintenanceIssue issue = existing(tenantId, MaintenanceStatus.OPEN);
        when(maintenanceRepository.changeStatus(any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(maintenanceService.changeStatus(issue.id(), new MaintenanceStatusRequest("IN_PROGRESS")));

        assertInstanceOf(ConflictException.class, error);
        assertEquals(MaintenanceService.CHANGED_CONCURRENTLY, error.getMessage());
    }

    // ---------- tenant history ----------

    @Test
    void tenantCanReadTheirOwnHistory() throws Exception {
        tenantExists(tenantId);
        List<MaintenanceIssue> history = List.of(issue(tenantId, MaintenanceStatus.RESOLVED), issue(tenantId, MaintenanceStatus.OPEN));
        when(maintenanceRepository.find(tenantId, null, null, null)).thenReturn(Future.succeededFuture(history));

        assertEquals(history, await(maintenanceService.tenantHistory(tenantUser, tenantId)));
    }

    @Test
    void tenantCannotReadAnotherTenantsHistory() throws Exception {
        Throwable error = awaitFailure(maintenanceService.tenantHistory(tenantUser, otherTenantId));

        assertInstanceOf(ForbiddenException.class, error);
        verifyNoInteractions(tenantRepository, maintenanceRepository);
    }

    @Test
    void staffCanReadAnyTenantsHistory() throws Exception {
        tenantExists(otherTenantId);
        when(maintenanceRepository.find(otherTenantId, null, null, null)).thenReturn(Future.succeededFuture(List.of()));

        assertEquals(List.of(), await(maintenanceService.tenantHistory(manager, otherTenantId)));
    }

    @Test
    void historyOfUnknownTenantFailsWith404() throws Exception {
        when(tenantRepository.findById(otherTenantId)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertInstanceOf(NotFoundException.class, awaitFailure(maintenanceService.tenantHistory(admin, otherTenantId)));
    }

    // ---------- helpers ----------

    private static MaintenanceRequest request(String tenantId, String title, String description, String category, String priority) {
        return new MaintenanceRequest(tenantId, title, description, category, priority);
    }

    private void tenantExists(UUID id) {
        Tenant tenant = new Tenant(id, "Ravi", "9876543210", null, LocalDate.of(2026, 10, 1), new BigDecimal("8500"),
                BigDecimal.ZERO, TenantStatus.ACTIVE, Instant.now());
        when(tenantRepository.findById(id)).thenReturn(Future.succeededFuture(Optional.of(tenant)));
    }

    /** An issue that the mocked repository returns from findById. */
    private MaintenanceIssue existing(UUID tenant, MaintenanceStatus status) {
        MaintenanceIssue issue = issue(tenant, status);
        when(maintenanceRepository.findById(issue.id())).thenReturn(Future.succeededFuture(Optional.of(issue)));
        return issue;
    }

    private static MaintenanceIssue issue(UUID tenant, MaintenanceStatus status) {
        boolean resolved = status == MaintenanceStatus.RESOLVED || status == MaintenanceStatus.CLOSED;
        return new MaintenanceIssue(UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Tap leaking", "Since morning", MaintenanceCategory.PLUMBING, MaintenancePriority.HIGH, status, null,
                Instant.now(), Instant.now(), resolved ? Instant.now() : null);
    }

    private static User user(Role role, UUID tenant) {
        return new User(UUID.randomUUID(), "Someone", "someone@example.com", "hash", role, Instant.now(), tenant);
    }
}
