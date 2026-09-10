package com.kaffe.menuadmin.service.ai;

import com.kaffe.common.exception.BadRequestException;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftCategory;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftPayload;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftProduct;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftOptionGroup;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiMenuDraftRulesTest {

    @Test void missingPriceCannotBePublishedEvenIfAiClaimsItIsReviewed() {
        var draft = payload(new DraftProduct("Latte", null, null, null, 0, false, List.of()));
        assertThat(AiMenuDraftRules.normalize(draft).categories().getFirst().products().getFirst().needsReview()).isTrue();
        assertThatThrownBy(() -> AiMenuDraftRules.requirePublishable(draft)).isInstanceOf(BadRequestException.class);
        AiMenuDraftRules.requirePublishable(payload(new DraftProduct("Cortesía", null, 0, "Gratis", 0, false, List.of())));
    }

    @Test void sizesKeepIncrementalPricesAndExplicitDefaults() {
        var group = new DraftOptionGroup("Tamaño", true, 1, 1, List.of(
                new DraftOption("Chico", 0, true), new DraftOption("Grande", 1500, false)));
        var draft = payload(new DraftProduct("Latte", "Café con leche", 6000, "$60", 0, false, List.of(), List.of(group)));
        AiMenuDraftRules.requirePublishable(draft);
        assertThat(AiMenuDraftRules.normalize(draft).categories().getFirst().products().getFirst().optionGroups())
                .containsExactly(group);
    }

    @Test void unknownAddonPricesAndImpossibleSelectionRulesCannotPublish() {
        var unknown = new DraftOptionGroup("Extras", false, 0, 1, List.of(new DraftOption("Avena", null, false)));
        assertThatThrownBy(() -> AiMenuDraftRules.requirePublishable(payload(new DraftProduct(
                "Latte", null, 6000, "$60", 0, false, List.of(), List.of(unknown)))))
                .isInstanceOf(BadRequestException.class);
        var invalid = new DraftOptionGroup("Tamaño", true, 2, 1, List.of(new DraftOption("Chico", 0, false)));
        assertThatThrownBy(() -> AiMenuDraftRules.normalize(payload(new DraftProduct(
                "Latte", null, 6000, "$60", 0, false, List.of(), List.of(invalid)))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test void jsonPricesAreNeverCoercedFromDecimalsOrStrings() {
        var mapper = new ObjectMapper();
        for (String price : List.of("12.9", "\"4500\"", "2147483648")) {
            assertThatThrownBy(() -> mapper.readValue("{\"name\":\"Café\",\"basePriceMinor\":" + price + "}", DraftProduct.class))
                    .isInstanceOf(Exception.class);
        }
    }

    private DraftPayload payload(DraftProduct product) {
        return new DraftPayload("Menú de prueba", "MXN", List.of(new DraftCategory("Cafés", null, 0, List.of(product))), List.of());
    }

    @Test
    void normalizesStructureAndKeepsHumanReviewRequired() {
        DraftPayload normalized = AiMenuDraftRules.normalize(new DraftPayload(
                "  Menú de casa  ",
                "mxn",
                List.of(new DraftCategory(
                        "  Cafés  ",
                        null,
                        99,
                        List.of(new DraftProduct(
                                " Espresso ",
                                " Doble  carga ",
                                4800,
                                "$48",
                                9,
                                false,
                                List.of("Confirmar tamaño")
                        ))
                )),
                List.of()
        ));

        assertThat(normalized.title()).isEqualTo("Menú de casa");
        assertThat(normalized.currencyCode()).isEqualTo("MXN");
        assertThat(normalized.categories().getFirst().sortOrder()).isZero();
        assertThat(normalized.categories().getFirst().products().getFirst().sortOrder()).isZero();
        assertThat(normalized.categories().getFirst().products().getFirst().needsReview()).isTrue();
    }

    @Test
    void refusesPublicationWhileAnyRecognizedValueNeedsReview() {
        DraftPayload draft = new DraftPayload(
                null,
                "MXN",
                List.of(new DraftCategory(
                        "Bebidas",
                        null,
                        0,
                        List.of(new DraftProduct(
                                "Té",
                                null,
                                0,
                                null,
                                0,
                                true,
                                List.of("Precio ilegible")
                        ))
                )),
                List.of()
        );

        assertThatThrownBy(() -> AiMenuDraftRules.requirePublishable(draft))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Revisa");
    }

    @Test
    void rejectsDuplicateCategoriesAndUnsupportedImages() {
        DraftCategory category = new DraftCategory(
                "Café",
                null,
                0,
                List.of(new DraftProduct("Latte", null, 7000, "$70", 0, false, List.of()))
        );
        assertThatThrownBy(() -> AiMenuDraftRules.normalize(new DraftPayload(
                null, "MXN", List.of(category, category), List.of())))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> AiMenuDraftService.validateImage(new byte[2048]))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("JPEG");
    }
}
