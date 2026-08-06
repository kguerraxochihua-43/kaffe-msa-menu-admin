package com.kaffe.menuadmin.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutRecommendationRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsAValidTenantCuratedRecommendation() {
        CheckoutRecommendationRequest request = new CheckoutRecommendationRequest(
                10L,
                20L,
                30L,
                "Completa tu orden",
                100,
                true,
                null,
                null
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsMissingProductsAndOutOfRangeEditorialFields() {
        CheckoutRecommendationRequest request = new CheckoutRecommendationRequest(
                null,
                -1L,
                0L,
                "x".repeat(121),
                1001,
                true,
                null,
                null
        );

        Set<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(invalidFields).containsExactlyInAnyOrder(
                "productId",
                "triggerProductId",
                "locationId",
                "message",
                "priority"
        );
    }
}
