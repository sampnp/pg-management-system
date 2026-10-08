package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Login details for a tenant's account. The role is always TENANT and the name is taken from the tenant. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TenantAccountRequest(String email, String password) {

    @Override
    public String toString() {
        return "TenantAccountRequest[email=%s]".formatted(email);
    }
}
