package com.kaffe.menuadmin.dto;

import java.time.OffsetDateTime;

public record CheckoutRecommendationResponse(
        Long recommendationId,
        Long cafeteriaId,
        Long locationId,
        String locationName,
        Long productId,
        String productName,
        Long triggerProductId,
        String triggerProductName,
        String message,
        int priority,
        boolean active,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
