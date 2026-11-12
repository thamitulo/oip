package za.co.oip.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.oip.support.IntegrationTest;
import za.co.oip.tenancy.TenantRole;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The Java role→permission mapping must match the seeded reference data. */
class RolePermissionSyncIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void tenantRolePermissionsMatchDatabase() {
        for (TenantRole role : TenantRole.values()) {
            Set<String> db = Set.copyOf(jdbc.queryForList(
                    "select permission_code from role_permissions where role_code = ?", String.class, role.name()));
            Set<String> code = role.permissions().stream().map(Enum::name).collect(Collectors.toSet());
            assertThat(code).as("permissions of %s", role).isEqualTo(db);
        }
    }
}
