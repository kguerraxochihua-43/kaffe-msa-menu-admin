package com.kaffe.menuadmin.dto;

public record CategoryResponse(
        Long categoryId,
        Long cafeteriaId,
        String name,
        String description,
        int sortOrder,
        boolean active
) {
}
