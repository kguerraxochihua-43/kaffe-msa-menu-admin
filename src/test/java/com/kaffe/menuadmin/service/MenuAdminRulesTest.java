package com.kaffe.menuadmin.service;

import com.kaffe.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuAdminRulesTest {

    @Test
    void acceptsACompleteProductAndNonNegativePrice() {
        assertThatCode(() -> MenuAdminRules.validateProduct(Map.of(
                "name", "Latte vainilla",
                "description", "Espresso, leche y vainilla",
                "basePrice", 7900,
                "sortOrder", 2
        ), true)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNegativePricesAndMissingNames() {
        assertThatThrownBy(() -> MenuAdminRules.validateProduct(Map.of(
                "name", "Latte",
                "basePrice", -1
        ), true)).isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> MenuAdminRules.validateCategory(Map.of(), true))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsInconsistentAddonSelection() {
        assertThatThrownBy(() -> MenuAdminRules.validateAddonGroup(Map.of(
                "name", "Tipo de leche",
                "required", true,
                "minSelection", 0,
                "maxSelection", 1
        ), true)).isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> MenuAdminRules.validateAddonGroup(Map.of(
                "name", "Sabores",
                "minSelection", 2,
                "maxSelection", 1
        ), true)).isInstanceOf(BadRequestException.class);
    }
}
