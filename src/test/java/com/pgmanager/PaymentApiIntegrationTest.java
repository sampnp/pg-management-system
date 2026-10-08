package com.pgmanager;

import com.pgmanager.config.Database;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests for /api/payments and /api/tenants/:tenantId/payments. */
class PaymentApiIntegrationTest extends ApiTestBase {

    private static final String UNKNOWN_ID = UUID.randomUUID().toString();

    private static String token;

    @BeforeAll
    static void loginAsManager() throws Exception {
        token = registerAndLogin("MANAGER");
    }

    @Test
    void fullPaymentFlow() throws Exception {
        String tenantId = createTenant("Payment Flow Tenant");

        // Create
        HttpResponse<Buffer> created = send(POST, "/api/payments", token,
                paymentJson(tenantId, 8000, "2026-10", "PAID", "UPI").put("paymentDate", "2026-10-09").put("receiptId", uniqueReceipt()));
        assertEquals(201, created.statusCode(), created::bodyAsString);
        JsonObject payment = created.bodyAsJsonObject();
        String paymentId = payment.getString("id");
        assertEquals(tenantId, payment.getString("tenantId"));
        assertEquals("2026-10", payment.getString("rentMonth"));
        assertEquals("2026-10-09", payment.getString("paymentDate"));
        assertEquals("UPI", payment.getString("paymentMethod"));
        assertEquals("PAID", payment.getString("status"));
        assertEquals(8000, payment.getNumber("amount").intValue());

        // Get, list, tenant history
        assertEquals(paymentId, getJson("/api/payments/" + paymentId).getString("id"));
        assertTrue(ids(getArray("/api/payments")).contains(paymentId));
        assertEquals(List.of(paymentId), ids(getArray("/api/tenants/" + tenantId + "/payments")));

        // Update (correct the amount and method), then read it back
        HttpResponse<Buffer> updated = send(PUT, "/api/payments/" + paymentId, token,
                paymentJson(null, 8500, "2026-10", "PAID", "BANK_TRANSFER").put("paymentDate", "2026-10-10"));
        assertEquals(200, updated.statusCode(), updated::bodyAsString);

        JsonObject afterUpdate = getJson("/api/payments/" + paymentId);
        assertEquals(8500, afterUpdate.getNumber("amount").intValue());
        assertEquals("BANK_TRANSFER", afterUpdate.getString("paymentMethod"));
        assertEquals("2026-10-10", afterUpdate.getString("paymentDate"));
        assertEquals(tenantId, afterUpdate.getString("tenantId"));
        assertTrue(Instant.parse(afterUpdate.getString("updatedAt")).isAfter(Instant.parse(afterUpdate.getString("createdAt"))));
    }

    @Test
    void allMethodsAndStatusesAreSupported() throws Exception {
        String tenantId = createTenant("Many Methods Tenant");

        for (String method : List.of("UPI", "CASH", "BANK_TRANSFER")) {
            HttpResponse<Buffer> response = send(POST, "/api/payments", token,
                    paymentJson(tenantId, 1000, "2026-09", "PAID", method).put("paymentDate", "2026-09-05"));
            assertEquals(201, response.statusCode(), response::bodyAsString);
            assertEquals(method, response.bodyAsJsonObject().getString("paymentMethod"));
        }

        // A PENDING payment (rent due, not received yet) needs no date or method
        HttpResponse<Buffer> pending = send(POST, "/api/payments", token,
                new JsonObject().put("tenantId", tenantId).put("amount", 8000).put("rentMonth", "2026-11").put("status", "PENDING"));
        assertEquals(201, pending.statusCode(), pending::bodyAsString);
        assertNull(pending.bodyAsJsonObject().getString("paymentDate"));
        assertNull(pending.bodyAsJsonObject().getString("paymentMethod"));
    }

    @Test
    void filtersCanBeCombined() throws Exception {
        String alice = createTenant("Filter Alice");
        String bob = createTenant("Filter Bob");
        String alicePaidSep = createPayment(alice, "2026-09", "PAID");
        String alicePendingOct = createPayment(alice, "2026-10", "PENDING");
        String bobPaidOct = createPayment(bob, "2026-10", "PAID");

        assertEquals(List.of(alicePendingOct, alicePaidSep), ids(getArray("/api/payments?tenantId=" + alice)));
        assertEquals(List.of(bobPaidOct), ids(getArray("/api/payments?tenantId=" + bob + "&rentMonth=2026-10")));
        assertEquals(List.of(alicePendingOct), ids(getArray("/api/payments?tenantId=" + alice + "&status=PENDING")));

        List<String> paidInOctober = ids(getArray("/api/payments?status=PAID&rentMonth=2026-10"));
        assertTrue(paidInOctober.contains(bobPaidOct));
        assertFalse(paidInOctober.contains(alicePendingOct));
        assertFalse(paidInOctober.contains(alicePaidSep));

        assertError(send(GET, "/api/payments?status=LATE", token, null), 400, "BAD_REQUEST", "status must be PAID or PENDING");
        assertError(send(GET, "/api/payments?rentMonth=2026-1", token, null), 400, "BAD_REQUEST", "rentMonth must be in YYYY-MM format");
    }

    @Test
    void tenantHistoryIsNewestMonthFirstAndEmptyWhenNone() throws Exception {
        String tenantId = createTenant("History Tenant");
        assertEquals(0, getArray("/api/tenants/" + tenantId + "/payments").size());

        String august = createPayment(tenantId, "2026-08", "PAID");
        String october = createPayment(tenantId, "2026-10", "PAID");
        String september = createPayment(tenantId, "2026-09", "PAID");

        assertEquals(List.of(october, september, august), ids(getArray("/api/tenants/" + tenantId + "/payments")));
    }

    // ---------- duplicates ----------

    @Test
    void sameReceiptCannotBeRecordedTwice() throws Exception {
        String tenantId = createTenant("Receipt Tenant");
        String receipt = uniqueReceipt();
        JsonObject body = paymentJson(tenantId, 8000, "2026-10", "PAID", "UPI").put("paymentDate", "2026-10-09").put("receiptId", receipt);
        assertEquals(201, send(POST, "/api/payments", token, body).statusCode());

        assertError(send(POST, "/api/payments", token, body), 409, "CONFLICT", "A payment with this receiptId already exists");

        // Changing another payment to use the same receipt is also rejected
        String other = createPayment(tenantId, "2026-11", "PAID");
        assertError(send(PUT, "/api/payments/" + other, token, body), 409, "CONFLICT", "A payment with this receiptId already exists");
    }

    @Test
    void onlyOnePendingRecordPerTenantAndMonthButInstallmentsAreAllowed() throws Exception {
        String tenantId = createTenant("Installment Tenant");
        createPayment(tenantId, "2026-10", "PENDING");

        assertError(send(POST, "/api/payments", token, paymentJson(tenantId, 8000, "2026-10", "PENDING", null)), 409, "CONFLICT",
                "Tenant already has a PENDING payment for this month");

        // Paying the same month in two parts is normal
        createPayment(tenantId, "2026-10", "PAID");
        createPayment(tenantId, "2026-10", "PAID");
        assertEquals(3, getArray("/api/payments?tenantId=" + tenantId + "&rentMonth=2026-10").size());
    }

    // ---------- payment history survives checkout ----------

    @Test
    void paymentHistoryRemainsAfterCheckOut() throws Exception {
        String tenantId = createTenant("Leaving Tenant");
        String bedId = createBed();
        assertEquals(200, send(POST, "/api/tenants/" + tenantId + "/check-in", token, new JsonObject().put("bedId", bedId)).statusCode());
        String rentPayment = createPayment(tenantId, "2026-10", "PAID");

        assertEquals(200, send(POST, "/api/tenants/" + tenantId + "/check-out", token, null).statusCode());

        assertEquals(List.of(rentPayment), ids(getArray("/api/tenants/" + tenantId + "/payments")));
        // Final dues can still be recorded after checkout, and the tenant can't be deleted while payments exist
        createPayment(tenantId, "2026-11", "PAID");
        assertEquals(2, getArray("/api/tenants/" + tenantId + "/payments").size());
        assertError(send(DELETE, "/api/tenants/" + tenantId, token, null), 409, "CONFLICT",
                "Tenant cannot be deleted because they have occupancy, payment or maintenance history");
    }

    // ---------- errors ----------

    @Test
    void requestsWithoutTokenReturn401() throws Exception {
        String message = "Missing or invalid Authorization header";
        assertError(send(POST, "/api/payments", null, paymentJson(UNKNOWN_ID, 100, "2026-10", "PENDING", null)), 401, "UNAUTHORIZED", message);
        assertError(send(GET, "/api/payments", null, null), 401, "UNAUTHORIZED", message);
        assertError(send(GET, "/api/payments/" + UNKNOWN_ID, null, null), 401, "UNAUTHORIZED", message);
        assertError(send(PUT, "/api/payments/" + UNKNOWN_ID, null, paymentJson(null, 100, "2026-10", "PENDING", null)), 401, "UNAUTHORIZED", message);
        assertError(send(GET, "/api/tenants/" + UNKNOWN_ID + "/payments", null, null), 401, "UNAUTHORIZED", message);
        assertError(send(GET, "/api/payments", "not.a.jwt", null), 401, "UNAUTHORIZED", "Invalid or expired token");
    }

    @Test
    void adminAndManagerCanBothRecordPayments() throws Exception {
        String tenantId = createTenant("Role Tenant");
        for (String role : List.of("ADMIN", "MANAGER")) {
            HttpResponse<Buffer> response = send(POST, "/api/payments", registerAndLogin(role),
                    paymentJson(tenantId, 500, "2026-10", "PAID", "CASH").put("paymentDate", "2026-10-01"));
            assertEquals(201, response.statusCode(), () -> role + ": " + response.bodyAsString());
        }
    }

    @Test
    void invalidInputReturns400() throws Exception {
        String tenantId = createTenant("Invalid Input Tenant");
        assertError(send(POST, "/api/payments", token, paymentJson(tenantId, 0, "2026-10", "PENDING", null)), 400, "BAD_REQUEST",
                "amount must be greater than 0");
        assertError(send(POST, "/api/payments", token, paymentJson(tenantId, -10, "2026-10", "PENDING", null)), 400, "BAD_REQUEST",
                "amount must be greater than 0");
        assertError(send(POST, "/api/payments", token, paymentJson(tenantId, 100, "2026-10", "PAID", "CHEQUE").put("paymentDate", "2026-10-01")),
                400, "BAD_REQUEST", "paymentMethod must be UPI, CASH or BANK_TRANSFER");
        assertError(send(POST, "/api/payments", token, paymentJson(tenantId, 100, "2026-10", "DONE", null)), 400, "BAD_REQUEST",
                "status must be PAID or PENDING");
        assertError(send(POST, "/api/payments", token, paymentJson(tenantId, 100, "2026/10", "PENDING", null)), 400, "BAD_REQUEST",
                "rentMonth must be in YYYY-MM format");
        assertError(send(POST, "/api/payments", token, paymentJson(tenantId, 100, "2026-10", "PAID", "UPI").put("paymentDate", "2026-10-32")),
                400, "BAD_REQUEST", "paymentDate must be a valid date in YYYY-MM-DD format");
        assertError(send(POST, "/api/payments", token, paymentJson(null, 100, "2026-10", "PENDING", null)), 400, "BAD_REQUEST",
                "tenantId is required");
        assertError(send(POST, "/api/payments", token, paymentJson("abc", 100, "2026-10", "PENDING", null)), 400, "BAD_REQUEST",
                "tenantId must be a valid UUID");
        assertError(send(GET, "/api/payments/abc", token, null), 400, "BAD_REQUEST", "id must be a valid UUID");
    }

    @Test
    void unknownTenantOrPaymentReturns404() throws Exception {
        assertError(send(POST, "/api/payments", token, paymentJson(UNKNOWN_ID, 100, "2026-10", "PENDING", null)), 404, "NOT_FOUND",
                "Tenant not found");
        assertError(send(GET, "/api/tenants/" + UNKNOWN_ID + "/payments", token, null), 404, "NOT_FOUND", "Tenant not found");
        assertError(send(GET, "/api/payments/" + UNKNOWN_ID, token, null), 404, "NOT_FOUND", "Payment not found");
        assertError(send(PUT, "/api/payments/" + UNKNOWN_ID, token, paymentJson(null, 100, "2026-10", "PENDING", null)), 404, "NOT_FOUND",
                "Payment not found");
    }

    @Test
    void paymentCannotBeMovedToAnotherTenant() throws Exception {
        String paymentId = createPayment(createTenant("Original Owner"), "2026-10", "PAID");
        String otherTenant = createTenant("Someone Else");

        assertError(send(PUT, "/api/payments/" + paymentId, token,
                        paymentJson(otherTenant, 8000, "2026-10", "PAID", "UPI").put("paymentDate", "2026-10-09")),
                400, "BAD_REQUEST", "tenantId of a payment cannot be changed");
    }

    /** The service validates first, but the database constraints also refuse bad data on their own. */
    @Test
    void databaseConstraintsRejectInvalidPayments() throws Exception {
        String tenantId = createTenant("Constraint Tenant");
        Pool db = Database.createPool(vertx, databaseConfig());
        String insert = "INSERT INTO payments (tenant_id, amount, rent_month, payment_date, payment_method, status) VALUES ($1, $2, $3, $4, $5, $6)";
        try {
            UUID id = UUID.fromString(tenantId);
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(id, 0, "2026-10", null, null, "PENDING"))), "amount > 0");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(id, 100, "2026-13", null, null, "PENDING"))), "valid month");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(id, 100, "2026-10", null, "CHEQUE", "PENDING"))), "valid method");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(id, 100, "2026-10", null, null, "PAID"))), "PAID needs date+method");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(UUID.randomUUID(), 100, "2026-10", null, null, "PENDING"))), "tenant must exist");
        } finally {
            await(db.close());
        }
    }

    // ---------- helpers ----------

    private static JsonObject paymentJson(String tenantId, int amount, String rentMonth, String status, String method) {
        JsonObject body = new JsonObject().put("amount", amount).put("rentMonth", rentMonth).put("status", status);
        if (tenantId != null) {
            body.put("tenantId", tenantId);
        }
        if (method != null) {
            body.put("paymentMethod", method);
        }
        return body;
    }

    /** Creates a payment of 4000 for the month; PAID ones are paid by UPI on the 5th. */
    private static String createPayment(String tenantId, String rentMonth, String status) throws Exception {
        JsonObject body = "PAID".equals(status)
                ? paymentJson(tenantId, 4000, rentMonth, status, "UPI").put("paymentDate", rentMonth + "-05")
                : paymentJson(tenantId, 4000, rentMonth, status, null);
        return createAndGetId("/api/payments", body);
    }

    private static String createTenant(String name) throws Exception {
        return createAndGetId("/api/tenants", new JsonObject().put("name", name).put("phone", "9876543210")
                .put("joiningDate", "2026-08-01").put("monthlyRent", 8000).put("securityDeposit", 10000));
    }

    private static String createBed() throws Exception {
        String propertyId = createAndGetId("/api/properties",
                new JsonObject().put("name", "Payment Test PG").put("address", "2 Park Street").put("city", "Kolkata"));
        String roomId = createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "101").put("capacity", 1));
        return createAndGetId("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "A"));
    }

    private static String uniqueReceipt() {
        return "UPI-" + UUID.randomUUID();
    }

    private static List<String> ids(JsonArray array) {
        return array.stream().map(item -> ((JsonObject) item).getString("id")).toList();
    }

    private static String createAndGetId(String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = send(POST, path, token, body);
        assertEquals(201, response.statusCode(), () -> "Setup request failed: " + response.bodyAsString());
        return response.bodyAsJsonObject().getString("id");
    }

    private static JsonObject getJson(String path) throws Exception {
        HttpResponse<Buffer> response = send(GET, path, token, null);
        assertEquals(200, response.statusCode(), () -> "GET " + path + " failed: " + response.bodyAsString());
        return response.bodyAsJsonObject();
    }

    private static JsonArray getArray(String path) throws Exception {
        HttpResponse<Buffer> response = send(GET, path, token, null);
        assertEquals(200, response.statusCode(), () -> "GET " + path + " failed: " + response.bodyAsString());
        return response.bodyAsJsonArray();
    }
}
