package com.kaffe.menuadmin.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AiMenuDraftDtos {

    private AiMenuDraftDtos() {
    }

    public record DraftPayload(
            String title,
            String currencyCode,
            List<DraftCategory> categories,
            List<String> warnings
    ) {
    }

    public record DraftCategory(
            String name,
            String description,
            int sortOrder,
            List<DraftProduct> products
    ) {
    }

    public record DraftProduct(
            String name,
            String description,
            int basePriceMinor,
            String priceText,
            int sortOrder,
            boolean needsReview,
            List<String> reviewReasons
    ) {
    }

    public record UpdateRequest(
            int expectedVersion,
            DraftPayload draft
    ) {
    }

    public record PublishRequest(int expectedVersion) {
    }

    public record DraftResponse(
            UUID draftId,
            long tenantId,
            long actorUserId,
            String status,
            int version,
            DraftPayload draft,
            Map<String, Object> publication,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime publishedAt,
            OffsetDateTime discardedAt
    ) {
    }
}
