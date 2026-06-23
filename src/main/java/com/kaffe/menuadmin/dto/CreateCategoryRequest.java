package com.kaffe.menuadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 1000)
        String description,

        Integer sortOrder
) {
    public int effectiveSortOrder() {
        return sortOrder == null ? 0 : sortOrder;
    }
}
