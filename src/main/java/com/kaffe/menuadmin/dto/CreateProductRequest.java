package com.kaffe.menuadmin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        @NotNull
        Long categoryId,

        @NotBlank
        @Size(max = 140)
        String name,

        @Size(max = 2000)
        String description,

        @Min(0)
        Integer basePrice,

        @Size(max = 1000)
        String imageUrl,

        Boolean featured,
        Boolean available,
        Integer sortOrder
) {
    public int effectiveBasePrice() {
        return basePrice == null ? 0 : basePrice;
    }

    public boolean effectiveFeatured() {
        return featured != null && featured;
    }

    public boolean effectiveAvailable() {
        return available == null || available;
    }

    public int effectiveSortOrder() {
        return sortOrder == null ? 0 : sortOrder;
    }
}
