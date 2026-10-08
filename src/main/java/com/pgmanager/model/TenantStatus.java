package com.pgmanager.model;

/** PENDING = registered, no bed yet; ACTIVE = checked in to a bed; CHECKED_OUT = left their bed. */
public enum TenantStatus {
    PENDING,
    ACTIVE,
    CHECKED_OUT
}
