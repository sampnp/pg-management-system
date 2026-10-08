package com.pgmanager.config;

/**
 * Account-related settings.
 *
 * @param allowPublicRegistration whether anyone may create a MANAGER account with POST /api/auth/register.
 *                                Off by default: staff accounts are then created by an ADMIN.
 * @param bootstrapAdminEmail     optional; together with bootstrapAdminPassword, creates the first ADMIN at
 *                                startup if there is no ADMIN yet (ignored once one exists)
 */
public record SecurityConfig(boolean allowPublicRegistration, String bootstrapAdminEmail, String bootstrapAdminPassword) {

    public SecurityConfig {
        boolean hasEmail = bootstrapAdminEmail != null && !bootstrapAdminEmail.isBlank();
        boolean hasPassword = bootstrapAdminPassword != null && !bootstrapAdminPassword.isBlank();
        if (hasEmail != hasPassword) {
            throw new IllegalStateException("Set both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD, or neither");
        }
    }

    public boolean hasBootstrapAdmin() {
        return bootstrapAdminEmail != null && !bootstrapAdminEmail.isBlank();
    }

    @Override
    public String toString() {
        return "SecurityConfig[allowPublicRegistration=%s, bootstrapAdminEmail=%s, bootstrapAdminPassword=***]"
                .formatted(allowPublicRegistration, bootstrapAdminEmail);
    }
}
