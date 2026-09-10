package com.kaffe.menuadmin.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

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
            List<String> warnings,
            String sourceText
    ) {
        public DraftPayload(String title, String currencyCode, List<DraftCategory> categories, List<String> warnings) {
            this(title, currencyCode, categories, warnings, null);
        }
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
            @JsonDeserialize(using = MenuMinorAmountDeserializer.class) Integer basePriceMinor,
            String priceText,
            int sortOrder,
            boolean needsReview,
            List<String> reviewReasons,
            List<DraftOptionGroup> optionGroups
    ) {
        public DraftProduct(String name, String description, Integer basePriceMinor, String priceText,
                            int sortOrder, boolean needsReview, List<String> reviewReasons) {
            this(name, description, basePriceMinor, priceText, sortOrder, needsReview, reviewReasons, List.of());
        }
    }

    public record DraftOptionGroup(String name, boolean required, int minSelection, int maxSelection,
                                   List<DraftOption> options) {
    }

    public record DraftOption(String name, @JsonDeserialize(using = MenuMinorAmountDeserializer.class) Integer priceMinor, boolean isDefault) {
    }

    public record CreateTextRequest(String idempotencyKey, String text) {
    }

    public record UpdateRequest(
            int expectedVersion,
            DraftPayload draft
    ) {
    }

    public record PublishRequest(int expectedVersion, boolean createSeparateMenu) {
        public PublishRequest(int expectedVersion) { this(expectedVersion, false); }
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
