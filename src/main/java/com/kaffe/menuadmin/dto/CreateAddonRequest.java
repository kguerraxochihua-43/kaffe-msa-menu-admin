package com.kaffe.menuadmin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAddonRequest(
        @NotNull
        Long addonGroupId,

        @NotBlank
        @Size(max = 120)
        String name,

        @Size(max = 1000)
        String description,

        @Min(0)
        Integer price,

        Boolean isDefault,

        Integer sortOrder
) {
    public int effectivePrice() {
        return price == null ? 0 : price;
    }

    public int effectiveSortOrder() {
        return sortOrder == null ? 0 : sortOrder;
    }

    public boolean effectiveDefault() {
        return isDefault != null && isDefault;
    }
}
