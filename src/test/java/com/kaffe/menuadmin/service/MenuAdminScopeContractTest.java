package com.kaffe.menuadmin.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MenuAdminScopeContractTest {

    @Test
    void menuAccessUsesPoliciesAndLocationAssignments() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/kaffe/menuadmin/service/MenuAdminService.java"
        ));

        assertThat(source)
                .contains("POLICY_MENU_READ = \"menu:read\"")
                .contains("POLICY_MENU_WRITE = \"menu:write\"")
                .contains("tenant_user_role_assignments")
                .contains("tenant_role_policies")
                .contains("ura.location_id is null or ura.location_id = l.location_id")
                .contains("ensureLocationExistsForAccess(cafeteriaId, locationId)")
                .contains("Location was not found for this cafeteria")
                .doesNotContain("MANAGER_ROLES");
    }
}
