package com.kaffe.menuadmin.service.ai;

import com.kaffe.common.exception.BadRequestException;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftCategory;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftPayload;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftProduct;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class AiMenuDraftRules {

    static final int MAX_CATEGORIES = 40;
    static final int MAX_PRODUCTS = 400;
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final long MAX_PRICE_MINOR = 100_000_000L;

    private AiMenuDraftRules() {
    }

    public static DraftPayload normalize(DraftPayload input) {
        if (input == null) {
            throw new BadRequestException("El borrador del menú es obligatorio");
        }
        String currency = required(input.currencyCode(), "La moneda es obligatoria", 3)
                .toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new BadRequestException("La moneda del borrador no es válida");
        }
        List<DraftCategory> rawCategories = input.categories() == null ? List.of() : input.categories();
        if (rawCategories.isEmpty() || rawCategories.size() > MAX_CATEGORIES) {
            throw new BadRequestException("El borrador debe contener entre 1 y 40 categorías");
        }

        List<DraftCategory> categories = new ArrayList<>();
        Set<String> categoryNames = new HashSet<>();
        int productCount = 0;
        for (int categoryIndex = 0; categoryIndex < rawCategories.size(); categoryIndex++) {
            DraftCategory rawCategory = rawCategories.get(categoryIndex);
            if (rawCategory == null) {
                throw new BadRequestException("El borrador contiene una categoría inválida");
            }
            String categoryName = required(rawCategory.name(), "Cada categoría necesita un nombre", 100);
            if (!categoryNames.add(categoryName.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("No puede haber categorías repetidas en el borrador");
            }
            List<DraftProduct> rawProducts = rawCategory.products() == null
                    ? List.of() : rawCategory.products();
            if (rawProducts.isEmpty()) {
                throw new BadRequestException("Cada categoría debe contener al menos un producto");
            }
            Set<String> productNames = new HashSet<>();
            List<DraftProduct> products = new ArrayList<>();
            for (int productIndex = 0; productIndex < rawProducts.size(); productIndex++) {
                DraftProduct rawProduct = rawProducts.get(productIndex);
                if (rawProduct == null) {
                    throw new BadRequestException("El borrador contiene un producto inválido");
                }
                String productName = required(rawProduct.name(), "Cada producto necesita un nombre", 140);
                if (!productNames.add(productName.toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException("No puede haber productos repetidos dentro de una categoría");
                }
                if (rawProduct.basePriceMinor() < 0 || rawProduct.basePriceMinor() > MAX_PRICE_MINOR) {
                    throw new BadRequestException("El precio de un producto está fuera del rango permitido");
                }
                List<String> reasons = normalizeStrings(rawProduct.reviewReasons(), 5, 180);
                boolean needsReview = rawProduct.needsReview() || !reasons.isEmpty();
                products.add(new DraftProduct(
                        productName,
                        optional(rawProduct.description(), 600),
                        rawProduct.basePriceMinor(),
                        optional(rawProduct.priceText(), 40),
                        productIndex,
                        needsReview,
                        reasons
                ));
                productCount++;
            }
            categories.add(new DraftCategory(
                    categoryName,
                    optional(rawCategory.description(), 500),
                    categoryIndex,
                    List.copyOf(products)
            ));
        }
        if (productCount > MAX_PRODUCTS) {
            throw new BadRequestException("El borrador no puede contener más de 400 productos");
        }
        return new DraftPayload(
                optional(input.title(), 160),
                currency,
                List.copyOf(categories),
                normalizeStrings(input.warnings(), 20, 220)
        );
    }

    public static void requirePublishable(DraftPayload draft) {
        DraftPayload normalized = normalize(draft);
        boolean pendingReview = normalized.categories().stream()
                .flatMap(category -> category.products().stream())
                .anyMatch(DraftProduct::needsReview);
        if (pendingReview) {
            throw new BadRequestException(
                    "Revisa los productos marcados antes de publicar el menú"
            );
        }
    }

    private static String required(String value, String message, int maxLength) {
        String normalized = optional(value, maxLength);
        if (normalized == null) {
            throw new BadRequestException(message);
        }
        return normalized;
    }

    private static String optional(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength) {
            throw new BadRequestException("Un texto del borrador excede la longitud permitida");
        }
        return normalized;
    }

    private static List<String> normalizeStrings(List<String> values, int maxItems, int maxLength) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > maxItems) {
            throw new BadRequestException("El borrador contiene demasiadas observaciones");
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = optional(value, maxLength);
            if (normalized != null && !result.contains(normalized)) result.add(normalized);
        }
        return List.copyOf(result);
    }
}
