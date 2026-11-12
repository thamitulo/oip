package za.co.oip.tenancy;

import za.co.oip.security.Permission;

import java.util.EnumSet;
import java.util.Set;

import static za.co.oip.security.Permission.COMPANY_PROFILE_EDIT;
import static za.co.oip.security.Permission.OPPORTUNITIES_VIEW;
import static za.co.oip.security.Permission.PIPELINE_EDIT;
import static za.co.oip.security.Permission.TENANT_MANAGE;
import static za.co.oip.security.Permission.USERS_MANAGE;

/**
 * Roles a user can hold within a tenant. The permission mapping mirrors the
 * {@code role_permissions} seed data (a test asserts that they stay in sync).
 * PLATFORM_ADMIN is not a tenant role; it is a flag on the user.
 */
public enum TenantRole {
    TENANT_OWNER(90, EnumSet.of(TENANT_MANAGE, USERS_MANAGE, COMPANY_PROFILE_EDIT, PIPELINE_EDIT, OPPORTUNITIES_VIEW)),
    TENANT_ADMIN(80, EnumSet.of(TENANT_MANAGE, USERS_MANAGE, COMPANY_PROFILE_EDIT, PIPELINE_EDIT, OPPORTUNITIES_VIEW)),
    PROCUREMENT_MANAGER(70, EnumSet.of(COMPANY_PROFILE_EDIT, PIPELINE_EDIT, OPPORTUNITIES_VIEW)),
    BID_MANAGER(60, EnumSet.of(PIPELINE_EDIT, OPPORTUNITIES_VIEW)),
    BID_RESEARCHER(50, EnumSet.of(PIPELINE_EDIT, OPPORTUNITIES_VIEW)),
    STANDARD_USER(40, EnumSet.of(PIPELINE_EDIT, OPPORTUNITIES_VIEW)),
    READ_ONLY_USER(10, EnumSet.of(OPPORTUNITIES_VIEW));

    private final int rank;
    private final Set<Permission> permissions;

    TenantRole(int rank, Set<Permission> permissions) {
        this.rank = rank;
        this.permissions = permissions;
    }

    public int rank() {
        return rank;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
