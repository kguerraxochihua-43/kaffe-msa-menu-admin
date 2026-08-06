package com.kaffe.menuadmin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CheckoutRecommendationRequest(
        @NotNull @Positive Long productId,
        @Positive Long triggerProductId,
        @Positive Long locationId,
        @Size(max = 120) String message,
        @Min(0) @Max(1000) Integer priority,
        Boolean active,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
) {
}
