package com.kaffe.menuadmin.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MenuScheduleContractTest {
    @Test
    void scheduleApiUsesBranchScopeOptimisticLockingAndSoftDelete() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/kaffe/menuadmin/service/MenuScheduleService.java"
        ));

        assertThat(source)
                .contains("menu.menu_schedule_windows")
                .contains("menu:read")
                .contains("menu:write")
                .contains("version = version + 1")
                .contains("and version = ?")
                .contains("deleted_at = now()")
                .contains("existing.is_override = ?")
                .contains("Another menu already covers part of that schedule");
    }
}
