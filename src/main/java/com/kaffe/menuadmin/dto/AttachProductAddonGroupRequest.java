package com.kaffe.menuadmin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AttachProductAddonGroupRequest(
        @NotNull
        Long addonGroupId,

        Boolean required,

        @Min(0)
        Integer minSelected,

        @Min(0)
        Integer maxSelected,

        Integer sortOrder
) {
    public boolean effectiveRequired() {
        return required != null && required;
    }

    public int effectiveMinSelected() {
        return minSelected == null ? 0 : minSelected;
    }

    public int effectiveSortOrder() {
        return sortOrder == null ? 0 : sortOrder;
    }
}
