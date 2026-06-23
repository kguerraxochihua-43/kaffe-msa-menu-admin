package com.kaffe.menuadmin.dto;

public record ProductResponse(
        Long productId,
        Long cafeteriaId,
        Long categoryId,
        String name,
        String description,
        int basePrice,
        String imageUrl,
        boolean featured,
        boolean available,
        int sortOrder
) {
}
