package com.pgmanager.service;

import com.pgmanager.dto.TenantRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Tenant;
import com.pgmanager.model.TenantStatus;
import com.pgmanager.repository.TenantRepository;
import io.vertx.core.Future;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public class TenantService {

    static final String TENANT_NOT_FOUND = "Tenant not found";
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 255;
    // Optional "+", then 7-15 digits (the international maximum). Spaces and dashes are removed first.
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public Future<Tenant> create(TenantRequest request) {
        return Future.succeededFuture(request)
                .map(TenantService::validate)
                .compose(valid -> tenantRepository.create(valid.name(), valid.phone(), valid.email(),
                        valid.joiningDate(), valid.monthlyRent(), valid.securityDeposit()));
    }

    public Future<List<Tenant>> findAll() {
        return tenantRepository.findAll();
    }

    public Future<Tenant> findById(UUID id) {
        return tenantRepository.findById(id)
                .map(tenant -> tenant.orElseThrow(() -> new NotFoundException(TENANT_NOT_FOUND)));
    }

    /** Updates personal details only. Status and bed change only through check-in/check-out. */
    public Future<Tenant> update(UUID id, TenantRequest request) {
        return Future.succeededFuture(request)
                .map(TenantService::validate)
                .compose(valid -> tenantRepository.update(id, valid.name(), valid.phone(), valid.email(),
                        valid.joiningDate(), valid.monthlyRent(), valid.securityDeposit()))
                .map(updated -> updated.orElseThrow(() -> new NotFoundException(TENANT_NOT_FOUND)));
    }

    /**
     * Only tenants that never stayed in a bed can be deleted. A checked-in tenant is rejected here;
     * a tenant with past stays is rejected by the database (history references them), so history is never lost.
     */
    public Future<Void> delete(UUID id) {
        return findById(id)
                .compose(tenant -> {
                    if (tenant.status() == TenantStatus.ACTIVE) {
                        return Future.failedFuture(new ConflictException(
                                "Cannot delete a tenant who is checked in; check them out first"));
                    }
                    return tenantRepository.delete(id);
                })
                .map(deleted -> {
                    if (!deleted) {
                        throw new NotFoundException(TENANT_NOT_FOUND);
                    }
                    return null;
                });
    }

    /** Validated and normalized tenant fields. */
    private record TenantDetails(String name, String phone, String email, LocalDate joiningDate,
                                 BigDecimal monthlyRent, BigDecimal securityDeposit) {
    }

    private static TenantDetails validate(TenantRequest request) {
        Validation.requireBody(request);
        return new TenantDetails(
                Validation.requireText(request.name(), "name", MAX_NAME_LENGTH),
                validatePhone(request.phone()),
                validateEmail(request.email()),
                validateJoiningDate(request.joiningDate()),
                Validation.requireAmount(request.monthlyRent(), "monthlyRent", false),
                Validation.requireAmount(request.securityDeposit(), "securityDeposit", true));
    }

    private static String validatePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new BadRequestException("phone is required");
        }
        String normalized = phone.replaceAll("[\\s-]", "");
        if (!PHONE_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException("phone must be 7 to 15 digits, optionally starting with +");
        }
        return normalized;
    }

    /** Email is optional (the column allows NULL). If given, it is trimmed, lower-cased and checked. */
    private static String validateEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException("email is not valid");
        }
        return normalized;
    }

    private static LocalDate validateJoiningDate(String joiningDate) {
        if (joiningDate == null || joiningDate.isBlank()) {
            throw new BadRequestException("joiningDate is required");
        }
        return Validation.parseDate(joiningDate, "joiningDate");
    }
}
