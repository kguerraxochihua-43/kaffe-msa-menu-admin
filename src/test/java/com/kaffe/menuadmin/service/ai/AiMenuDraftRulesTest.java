package com.kaffe.menuadmin.service.ai;

import com.kaffe.common.exception.BadRequestException;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftCategory;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftPayload;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftProduct;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiMenuDraftRulesTest {

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
