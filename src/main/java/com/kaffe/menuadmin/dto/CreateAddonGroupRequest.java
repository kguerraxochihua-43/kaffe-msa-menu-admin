package com.kaffe.menuadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CreateAddonGroupRequest(
        @NotBlank
        @Size(max = 120)
        String name,

        @Size(max = 1000)
        String description,

        Boolean required,

        @Min(0)
        Integer minSelection,

        @Min(0)
        Integer maxSelection,

        Integer sortOrder
) {
    public boolean effectiveRequired() {
        return required != null && required;
    }

    public int effectiveMinSelection() {
        return minSelection == null ? 0 : minSelection;
    }

    public int effectiveSortOrder() {
        return sortOrder == null ? 0 : sortOrder;
    }
}
