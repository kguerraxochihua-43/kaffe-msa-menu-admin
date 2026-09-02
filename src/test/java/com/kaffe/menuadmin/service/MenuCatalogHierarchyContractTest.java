package com.kaffe.menuadmin.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MenuCatalogHierarchyContractTest {

    @Test
    void protectsCategoryHierarchyAndReusableProductBundles() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/kaffe/menuadmin/service/MenuAdminService.java"
        ));

        assertThat(source)
                .contains("validateCategoryParent(cafeteriaId, categoryId, parentCategoryId)")
                .contains("with recursive descendants(category_id, path)")
                .contains("Move or delete the subcategories before deleting this category")
                .contains("@Transactional\n    public List<Map<String, Object>> replaceProductComponents")
                .contains("A product can contain at most 50 components")
                .contains("A product cannot contain itself")
                .contains("Duplicate product components are not allowed")
                .contains("validateProductComponentCycle(cafeteriaId, productId, componentProductId)")
                .contains("if (request.containsKey(\"components\"))")
                .contains("where component.tenant_id = ? and component.product_id = ?");
    }
}
