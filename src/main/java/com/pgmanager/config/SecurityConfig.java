package com.pgmanager.config;

import java.util.regex.Pattern;

/**
 * Account and browser-access settings.
 *
 * @param allowPublicRegistration whether anyone may create a MANAGER account with POST /api/auth/register.
 *                                Off by default: staff accounts are then created by an ADMIN.
 * @param bootstrapAdminEmail     optional; together with bootstrapAdminPassword, creates the first ADMIN at
 *                                startup if there is no ADMIN yet (ignored once one exists)
 * @param corsAllowedOrigin       optional; the one browser origin (e.g. https://app.example.com) allowed to call
 *                                the API from JavaScript. Not set = no CORS headers, so browsers block
 *                                cross-site calls. "*" is refused on purpose.
 */
public record SecurityConfig(boolean allowPublicRegistration, String bootstrapAdminEmail, String bootstrapAdminPassword,
                             String corsAllowedOrigin) {

    /** scheme://host[:port], no path, no trailing slash - the exact form browsers send in the Origin header. */
    private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.-]+(:\\d{1,5})?$");

    public SecurityConfig {
        boolean hasEmail = bootstrapAdminEmail != null && !bootstrapAdminEmail.isBlank();
        boolean hasPassword = bootstrapAdminPassword != null && !bootstrapAdminPassword.isBlank();
        if (hasEmail != hasPassword) {
            throw new IllegalStateException("Set both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD, or neither");
        }
        if (corsAllowedOrigin != null && !corsAllowedOrigin.isBlank() && !ORIGIN.matcher(corsAllowedOrigin).matches()) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGIN must be one origin like https://app.example.com "
                    + "(no wildcard, path or trailing slash), got: " + corsAllowedOrigin);
        }
    }

    /** Without browser access (CORS off). */
    public SecurityConfig(boolean allowPublicRegistration, String bootstrapAdminEmail, String bootstrapAdminPassword) {
        this(allowPublicRegistration, bootstrapAdminEmail, bootstrapAdminPassword, null);
    }

    public boolean hasBootstrapAdmin() {
        return bootstrapAdminEmail != null && !bootstrapAdminEmail.isBlank();
    }

    public boolean corsEnabled() {
        return corsAllowedOrigin != null && !corsAllowedOrigin.isBlank();
    }

    @Override
    public String toString() {
        return "SecurityConfig[allowPublicRegistration=%s, bootstrapAdminEmail=%s, bootstrapAdminPassword=***, corsAllowedOrigin=%s]"
                .formatted(allowPublicRegistration, bootstrapAdminEmail, corsAllowedOrigin);
    }
}
