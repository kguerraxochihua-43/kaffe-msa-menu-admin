package com.kaffe.menuadmin.dto;

import jakarta.validation.constraints.Min;

public record ConfigureLocationProductRequest(
        @Min(0)
        Integer priceOverride,

        Boolean available,
        Boolean featured,
        Integer sortOrder
) {
    public boolean effectiveAvailable() {
        return available == null || available;
    }

    public boolean effectiveFeatured() {
        return featured != null && featured;
    }

    public int effectiveSortOrder() {
        return sortOrder == null ? 0 : sortOrder;
    }
}
