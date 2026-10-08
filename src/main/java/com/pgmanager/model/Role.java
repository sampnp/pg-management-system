package com.pgmanager.model;

/** ADMIN and MANAGER are staff. A TENANT account belongs to one tenant and can only see that tenant's data. */
public enum Role {
    ADMIN,
    MANAGER,
    TENANT
}
