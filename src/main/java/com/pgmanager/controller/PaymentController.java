package com.pgmanager.controller;

import com.pgmanager.dto.PaymentRequest;
import com.pgmanager.service.PaymentService;
import io.vertx.ext.web.RoutingContext;

/** Thin HTTP layer for payments. There is no DELETE endpoint: payment records are kept as financial history. */
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** POST /api/payments */
    public void create(RoutingContext ctx) {
        paymentService.create(ctx.body().asPojo(PaymentRequest.class))
                .onSuccess(payment -> {
                    ctx.response().setStatusCode(201);
                    ctx.json(payment);
                })
                .onFailure(ctx::fail);
    }

    /** GET /api/payments?tenantId=...&status=PAID&rentMonth=2026-10 (all filters optional) */
    public void list(RoutingContext ctx) {
        paymentService.list(
                        ctx.queryParams().get("tenantId"),
                        ctx.queryParams().get("status"),
                        ctx.queryParams().get("rentMonth"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** GET /api/payments/:id */
    public void get(RoutingContext ctx) {
        paymentService.findById(PathParams.uuid(ctx, "id"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** PUT /api/payments/:id */
    public void update(RoutingContext ctx) {
        paymentService.update(PathParams.uuid(ctx, "id"), ctx.body().asPojo(PaymentRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** GET /api/tenants/:tenantId/payments */
    public void tenantHistory(RoutingContext ctx) {
        paymentService.tenantHistory(PathParams.uuid(ctx, "tenantId"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }
}
