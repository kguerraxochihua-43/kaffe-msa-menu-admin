package com.kaffe.menuadmin.dto;

public record LocationProductResponse(
        Long locationProductId,
        Long cafeteriaId,
        Long locationId,
        Long productId,
        Integer priceOverride,
        boolean available,
        boolean featured,
        int sortOrder
) {
}
