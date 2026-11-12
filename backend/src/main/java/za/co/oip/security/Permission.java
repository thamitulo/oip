package za.co.oip.security;

/** Fine-grained permissions granted to roles. Use with {@code @PreAuthorize("hasAuthority('...')")}. */
public enum Permission {
    TENANT_MANAGE,
    USERS_MANAGE,
    COMPANY_PROFILE_EDIT,
    PIPELINE_EDIT,
    OPPORTUNITIES_VIEW,
    SOURCES_MANAGE
}
