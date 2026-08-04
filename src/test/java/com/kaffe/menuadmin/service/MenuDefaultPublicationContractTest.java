package com.kaffe.menuadmin.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MenuDefaultPublicationContractTest {

    @Test
    void categoryCreationProvidesAPublicationFallback() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/kaffe/menuadmin/service/MenuAdminService.java"
        ));

        assertThat(source)
                .contains("ensureDefaultPublicationForCategory(cafeteriaId, categoryId)")
                .contains("pg_advisory_xact_lock")
                .contains("'Menú principal'")
                .contains("on conflict (menu_id, category_id) do update");
    }
}
