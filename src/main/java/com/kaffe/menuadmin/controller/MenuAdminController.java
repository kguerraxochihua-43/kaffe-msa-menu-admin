package com.kaffe.menuadmin.controller;

import com.kaffe.common.web.ApiResponse;
import com.kaffe.common.media.MediaCompleteRequest;
import com.kaffe.common.media.MediaUploadRequest;
import com.kaffe.common.media.PresignedMediaUpload;
import com.kaffe.menuadmin.dto.CheckoutRecommendationRequest;
import com.kaffe.menuadmin.dto.CheckoutRecommendationResponse;
import com.kaffe.menuadmin.service.MenuAdminService;
import com.kaffe.menuadmin.dto.MenuCatalogResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/menu-admin/cafeterias/{cafeteriaId}")
@RequiredArgsConstructor
public class MenuAdminController {

    private final MenuAdminService menuAdminService;

    @PutMapping("/locations/{locationId}/preparation-routing/{kind}/{entityId}")
    public ApiResponse<Void> updatePreparationRoute(@PathVariable Long cafeteriaId, @PathVariable Long locationId,
            @PathVariable String kind, @PathVariable Long entityId, @RequestBody Map<String,Object> request) {
        menuAdminService.updatePreparationRoute(cafeteriaId,locationId,kind,entityId,request);
        return ApiResponse.ok("Estación actualizada",null);
    }

    @GetMapping("/catalog")
    public ApiResponse<MenuCatalogResponse> getCatalog(
            @PathVariable Long cafeteriaId,
            @RequestParam(required = false) Long locationId
    ) {
        return ApiResponse.ok("Menu catalog found", menuAdminService.getCatalog(cafeteriaId, locationId));
    }

    @GetMapping("/checkout-recommendations")
    public ApiResponse<List<CheckoutRecommendationResponse>> listCheckoutRecommendations(
            @PathVariable Long cafeteriaId,
            @RequestParam(required = false) Long locationId
    ) {
        return ApiResponse.ok(
                "Checkout recommendations found",
                menuAdminService.listCheckoutRecommendations(cafeteriaId, locationId)
        );
    }

    @PostMapping("/checkout-recommendations")
    public ApiResponse<CheckoutRecommendationResponse> createCheckoutRecommendation(
            @PathVariable Long cafeteriaId,
            @Valid @RequestBody CheckoutRecommendationRequest request
    ) {
        return ApiResponse.ok(
                "Checkout recommendation created successfully",
                menuAdminService.createCheckoutRecommendation(cafeteriaId, request)
        );
    }

    @GetMapping("/checkout-recommendations/{recommendationId}")
    public ApiResponse<CheckoutRecommendationResponse> getCheckoutRecommendation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long recommendationId
    ) {
        return ApiResponse.ok(
                "Checkout recommendation found",
                menuAdminService.getCheckoutRecommendation(cafeteriaId, recommendationId)
        );
    }

    @PutMapping("/checkout-recommendations/{recommendationId}")
    public ApiResponse<CheckoutRecommendationResponse> updateCheckoutRecommendation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long recommendationId,
            @Valid @RequestBody CheckoutRecommendationRequest request
    ) {
        return ApiResponse.ok(
                "Checkout recommendation updated successfully",
                menuAdminService.updateCheckoutRecommendation(cafeteriaId, recommendationId, request)
        );
    }

    @DeleteMapping("/checkout-recommendations/{recommendationId}")
    public ApiResponse<Void> deleteCheckoutRecommendation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long recommendationId
    ) {
        menuAdminService.softDeleteCheckoutRecommendation(cafeteriaId, recommendationId);
        return ApiResponse.ok("Checkout recommendation deleted successfully", null);
    }

    @GetMapping("/settings/menu")
    public ApiResponse<Map<String, Object>> getMenuSettings(@PathVariable Long cafeteriaId) {
        return ApiResponse.ok("Menu settings found", menuAdminService.getMenuSettings(cafeteriaId));
    }

    @PutMapping("/settings/menu")
    public ApiResponse<Map<String, Object>> updateMenuSettings(
            @PathVariable Long cafeteriaId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Menu settings updated successfully", menuAdminService.updateMenuSettings(cafeteriaId, request));
    }

    @GetMapping("/languages")
    public ApiResponse<List<Map<String, Object>>> listLanguages(@PathVariable Long cafeteriaId) {
        return ApiResponse.ok("Languages found", menuAdminService.listLanguages(cafeteriaId));
    }

    @PutMapping("/languages/{languageCode}")
    public ApiResponse<Map<String, Object>> upsertLanguage(
            @PathVariable Long cafeteriaId,
            @PathVariable String languageCode,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Language saved successfully", menuAdminService.upsertLanguageConfig(cafeteriaId, languageCode, request));
    }

    @DeleteMapping("/languages/{languageCode}")
    public ApiResponse<Void> deleteLanguage(
            @PathVariable Long cafeteriaId,
            @PathVariable String languageCode
    ) {
        menuAdminService.softDeleteLanguage(cafeteriaId, languageCode);
        return ApiResponse.ok("Language deleted successfully", null);
    }

    @GetMapping("/currencies")
    public ApiResponse<List<Map<String, Object>>> listCurrencies(@PathVariable Long cafeteriaId) {
        return ApiResponse.ok("Currencies found", menuAdminService.listCurrencies(cafeteriaId));
    }

    @PutMapping("/currencies/{currencyCode}")
    public ApiResponse<Map<String, Object>> upsertCurrency(
            @PathVariable Long cafeteriaId,
            @PathVariable String currencyCode,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Currency saved successfully", menuAdminService.upsertCurrency(cafeteriaId, currencyCode, request));
    }

    @DeleteMapping("/currencies/{currencyCode}")
    public ApiResponse<Void> deleteCurrency(
            @PathVariable Long cafeteriaId,
            @PathVariable String currencyCode
    ) {
        menuAdminService.softDeleteCurrency(cafeteriaId, currencyCode);
        return ApiResponse.ok("Currency deleted successfully", null);
    }

    @GetMapping("/exchange-rates")
    public ApiResponse<List<Map<String, Object>>> listExchangeRates(@PathVariable Long cafeteriaId) {
        return ApiResponse.ok("Exchange rates found", menuAdminService.listExchangeRates(cafeteriaId));
    }

    @PostMapping("/exchange-rates")
    public ApiResponse<Map<String, Object>> createExchangeRate(
            @PathVariable Long cafeteriaId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Exchange rate created successfully", menuAdminService.createExchangeRate(cafeteriaId, request));
    }

    @PutMapping("/exchange-rates/{exchangeRateId}")
    public ApiResponse<Map<String, Object>> updateExchangeRate(
            @PathVariable Long cafeteriaId,
            @PathVariable Long exchangeRateId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Exchange rate updated successfully", menuAdminService.updateExchangeRate(cafeteriaId, exchangeRateId, request));
    }

    @DeleteMapping("/exchange-rates/{exchangeRateId}")
    public ApiResponse<Void> deleteExchangeRate(
            @PathVariable Long cafeteriaId,
            @PathVariable Long exchangeRateId
    ) {
        menuAdminService.softDeleteExchangeRate(cafeteriaId, exchangeRateId);
        return ApiResponse.ok("Exchange rate deleted successfully", null);
    }

    @GetMapping("/menus")
    public ApiResponse<List<Map<String, Object>>> listMenus(@PathVariable Long cafeteriaId) {
        return ApiResponse.ok("Menus found", menuAdminService.listMenus(cafeteriaId));
    }

    @PostMapping("/menus")
    public ApiResponse<Map<String, Object>> createMenu(
            @PathVariable Long cafeteriaId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Menu created successfully", menuAdminService.createMenu(cafeteriaId, request));
    }

    @GetMapping("/menus/{menuId}")
    public ApiResponse<Map<String, Object>> getMenu(
            @PathVariable Long cafeteriaId,
            @PathVariable Long menuId
    ) {
        return ApiResponse.ok("Menu found", menuAdminService.getMenu(cafeteriaId, menuId));
    }

    @PutMapping("/menus/{menuId}")
    public ApiResponse<Map<String, Object>> updateMenu(
            @PathVariable Long cafeteriaId,
            @PathVariable Long menuId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Menu updated successfully", menuAdminService.updateMenu(cafeteriaId, menuId, request));
    }

    @PutMapping("/menus/{menuId}/locations")
    public ApiResponse<Map<String, Object>> assignMenuLocations(
            @PathVariable Long cafeteriaId,
            @PathVariable Long menuId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Menu locations updated successfully", menuAdminService.updateMenu(cafeteriaId, menuId, request));
    }

    @DeleteMapping("/menus/{menuId}")
    public ApiResponse<Void> deleteMenu(
            @PathVariable Long cafeteriaId,
            @PathVariable Long menuId
    ) {
        menuAdminService.softDeleteMenu(cafeteriaId, menuId);
        return ApiResponse.ok("Menu deleted successfully", null);
    }

    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> listCategories(@PathVariable Long cafeteriaId) {
        return ApiResponse.ok("Categories found", menuAdminService.listCategories(cafeteriaId));
    }

    @PostMapping("/categories")
    public ApiResponse<Map<String, Object>> createCategory(
            @PathVariable Long cafeteriaId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Category created successfully", menuAdminService.createCategory(cafeteriaId, request));
    }

    @GetMapping("/categories/{categoryId}")
    public ApiResponse<Map<String, Object>> getCategory(
            @PathVariable Long cafeteriaId,
            @PathVariable Long categoryId
    ) {
        return ApiResponse.ok("Category found", menuAdminService.getCategory(cafeteriaId, categoryId));
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<Map<String, Object>> updateCategory(
            @PathVariable Long cafeteriaId,
            @PathVariable Long categoryId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Category updated successfully", menuAdminService.updateCategory(cafeteriaId, categoryId, request));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(
            @PathVariable Long cafeteriaId,
            @PathVariable Long categoryId
    ) {
        menuAdminService.softDeleteCategory(cafeteriaId, categoryId);
        return ApiResponse.ok("Category deleted successfully", null);
    }

    @GetMapping("/categories/{categoryId}/translations")
    public ApiResponse<List<Map<String, Object>>> listCategoryTranslations(
            @PathVariable Long cafeteriaId,
            @PathVariable Long categoryId
    ) {
        return ApiResponse.ok("Category translations found", menuAdminService.listCategoryTranslations(cafeteriaId, categoryId));
    }

    @PutMapping("/categories/{categoryId}/translations/{languageCode}")
    public ApiResponse<Map<String, Object>> upsertCategoryTranslation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long categoryId,
            @PathVariable String languageCode,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Category translation saved successfully", menuAdminService.upsertCategoryTranslation(cafeteriaId, categoryId, languageCode, request));
    }

    @DeleteMapping("/categories/{categoryId}/translations/{languageCode}")
    public ApiResponse<Void> deleteCategoryTranslation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long categoryId,
            @PathVariable String languageCode
    ) {
        menuAdminService.softDeleteCategoryTranslation(cafeteriaId, categoryId, languageCode);
        return ApiResponse.ok("Category translation deleted successfully", null);
    }

    @GetMapping("/products")
    public ApiResponse<List<Map<String, Object>>> listProducts(
            @PathVariable Long cafeteriaId,
            @RequestParam(required = false) Long categoryId
    ) {
        return ApiResponse.ok("Products found", menuAdminService.listProducts(cafeteriaId, categoryId));
    }

    @PostMapping("/products")
    public ApiResponse<Map<String, Object>> createProduct(
            @PathVariable Long cafeteriaId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Product created successfully", menuAdminService.createProduct(cafeteriaId, request));
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<Map<String, Object>> getProduct(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId
    ) {
        return ApiResponse.ok("Product found", menuAdminService.getProduct(cafeteriaId, productId));
    }

    @PutMapping("/products/{productId}")
    public ApiResponse<Map<String, Object>> updateProduct(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Product updated successfully", menuAdminService.updateProduct(cafeteriaId, productId, request));
    }

    @GetMapping("/products/{productId}/components")
    public ApiResponse<List<Map<String, Object>>> listProductComponents(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId
    ) {
        return ApiResponse.ok(
                "Product components found",
                menuAdminService.listProductComponents(cafeteriaId, productId)
        );
    }

    @PutMapping("/products/{productId}/components")
    public ApiResponse<List<Map<String, Object>>> replaceProductComponents(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok(
                "Product components updated successfully",
                menuAdminService.replaceProductComponents(cafeteriaId, productId, request)
        );
    }

    @PostMapping("/products/{productId}/image-upload-request")
    public ApiResponse<PresignedMediaUpload> createProductImageUploadRequest(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @RequestBody MediaUploadRequest request
    ) {
        return ApiResponse.ok(
                "Product image upload request created successfully",
                menuAdminService.createProductImageUpload(cafeteriaId, productId, request)
        );
    }

    @PostMapping("/products/{productId}/image-assets/{assetId}/complete")
    public ApiResponse<Map<String, Object>> completeProductImageUpload(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @PathVariable UUID assetId,
            @RequestBody(required = false) MediaCompleteRequest request
    ) {
        return ApiResponse.ok(
                "Product image completed successfully",
                menuAdminService.completeProductImageUpload(cafeteriaId, productId, assetId, request)
        );
    }

    @GetMapping("/products/{productId}/images")
    public ApiResponse<List<Map<String, Object>>> listProductImages(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId
    ) {
        return ApiResponse.ok("Product images found", menuAdminService.listProductImages(cafeteriaId, productId));
    }

    @PostMapping("/products/{productId}/images/upload-request")
    public ApiResponse<PresignedMediaUpload> createProductGalleryImageUploadRequest(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @RequestBody MediaUploadRequest request
    ) {
        return ApiResponse.ok(
                "Product image upload request created successfully",
                menuAdminService.createProductGalleryImageUpload(cafeteriaId, productId, request)
        );
    }

    @PostMapping("/products/{productId}/images/{assetId}/complete")
    public ApiResponse<Map<String, Object>> completeProductGalleryImageUpload(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @PathVariable UUID assetId,
            @RequestBody(required = false) MediaCompleteRequest request
    ) {
        return ApiResponse.ok(
                "Product image completed successfully",
                menuAdminService.completeProductImageUpload(cafeteriaId, productId, assetId, request)
        );
    }

    @PutMapping("/products/{productId}/images/{productImageId}")
    public ApiResponse<Map<String, Object>> updateProductImage(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @PathVariable Long productImageId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok(
                "Product image updated successfully",
                menuAdminService.updateProductImage(cafeteriaId, productId, productImageId, request)
        );
    }

    @DeleteMapping("/products/{productId}/images/{productImageId}")
    public ApiResponse<Void> deleteProductImage(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @PathVariable Long productImageId
    ) {
        menuAdminService.softDeleteProductImage(cafeteriaId, productId, productImageId);
        return ApiResponse.ok("Product image deleted successfully", null);
    }

    @PutMapping("/products/{productId}/locations")
    public ApiResponse<List<Map<String, Object>>> assignProductLocations(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Product locations updated successfully", menuAdminService.assignProductLocations(cafeteriaId, productId, request));
    }

    @PutMapping("/locations/{locationId}/products/{productId}")
    public ApiResponse<Map<String, Object>> configureLocationProduct(
            @PathVariable Long cafeteriaId,
            @PathVariable Long locationId,
            @PathVariable Long productId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok(
                "Location product configured successfully",
                menuAdminService.configureLocationProduct(cafeteriaId, locationId, productId, request)
        );
    }

    @DeleteMapping("/products/{productId}")
    public ApiResponse<Void> deleteProduct(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId
    ) {
        menuAdminService.softDeleteProduct(cafeteriaId, productId);
        return ApiResponse.ok("Product deleted successfully", null);
    }

    @GetMapping("/products/{productId}/translations")
    public ApiResponse<List<Map<String, Object>>> listProductTranslations(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId
    ) {
        return ApiResponse.ok("Product translations found", menuAdminService.listProductTranslations(cafeteriaId, productId));
    }

    @PutMapping("/products/{productId}/translations/{languageCode}")
    public ApiResponse<Map<String, Object>> upsertProductTranslation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @PathVariable String languageCode,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Product translation saved successfully", menuAdminService.upsertProductTranslation(cafeteriaId, productId, languageCode, request));
    }

    @DeleteMapping("/products/{productId}/translations/{languageCode}")
    public ApiResponse<Void> deleteProductTranslation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @PathVariable String languageCode
    ) {
        menuAdminService.softDeleteProductTranslation(cafeteriaId, productId, languageCode);
        return ApiResponse.ok("Product translation deleted successfully", null);
    }

    @GetMapping("/products/{productId}/prices")
    public ApiResponse<List<Map<String, Object>>> listProductPrices(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId
    ) {
        return ApiResponse.ok("Product prices found", menuAdminService.listProductPrices(cafeteriaId, productId));
    }

    @PutMapping("/products/{productId}/prices/{currencyCode}")
    public ApiResponse<Map<String, Object>> upsertProductPrice(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @PathVariable String currencyCode,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Product price saved successfully", menuAdminService.upsertProductPrice(cafeteriaId, productId, currencyCode, request));
    }

    @DeleteMapping("/products/{productId}/prices/{productPriceId}")
    public ApiResponse<Void> deleteProductPrice(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @PathVariable Long productPriceId
    ) {
        menuAdminService.softDeleteProductPrice(cafeteriaId, productId, productPriceId);
        return ApiResponse.ok("Product price deleted successfully", null);
    }

    @GetMapping("/addon-groups")
    public ApiResponse<List<Map<String, Object>>> listAddonGroups(@PathVariable Long cafeteriaId) {
        return ApiResponse.ok("Addon groups found", menuAdminService.listAddonGroups(cafeteriaId));
    }

    @PostMapping("/addon-groups")
    public ApiResponse<Map<String, Object>> createAddonGroup(
            @PathVariable Long cafeteriaId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Addon group created successfully", menuAdminService.createAddonGroup(cafeteriaId, request));
    }

    @GetMapping("/addon-groups/{addonGroupId}")
    public ApiResponse<Map<String, Object>> getAddonGroup(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonGroupId
    ) {
        return ApiResponse.ok("Addon group found", menuAdminService.getAddonGroup(cafeteriaId, addonGroupId));
    }

    @PutMapping("/addon-groups/{addonGroupId}")
    public ApiResponse<Map<String, Object>> updateAddonGroup(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonGroupId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Addon group updated successfully", menuAdminService.updateAddonGroup(cafeteriaId, addonGroupId, request));
    }

    @DeleteMapping("/addon-groups/{addonGroupId}")
    public ApiResponse<Void> deleteAddonGroup(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonGroupId
    ) {
        menuAdminService.softDeleteAddonGroup(cafeteriaId, addonGroupId);
        return ApiResponse.ok("Addon group deleted successfully", null);
    }

    @GetMapping("/addon-groups/{addonGroupId}/translations")
    public ApiResponse<List<Map<String, Object>>> listAddonGroupTranslations(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonGroupId
    ) {
        return ApiResponse.ok("Addon group translations found", menuAdminService.listAddonGroupTranslations(cafeteriaId, addonGroupId));
    }

    @PutMapping("/addon-groups/{addonGroupId}/translations/{languageCode}")
    public ApiResponse<Map<String, Object>> upsertAddonGroupTranslation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonGroupId,
            @PathVariable String languageCode,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Addon group translation saved successfully", menuAdminService.upsertAddonGroupTranslation(cafeteriaId, addonGroupId, languageCode, request));
    }

    @DeleteMapping("/addon-groups/{addonGroupId}/translations/{languageCode}")
    public ApiResponse<Void> deleteAddonGroupTranslation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonGroupId,
            @PathVariable String languageCode
    ) {
        menuAdminService.softDeleteAddonGroupTranslation(cafeteriaId, addonGroupId, languageCode);
        return ApiResponse.ok("Addon group translation deleted successfully", null);
    }

    @GetMapping("/addons")
    public ApiResponse<List<Map<String, Object>>> listAddons(
            @PathVariable Long cafeteriaId,
            @RequestParam(required = false) Long addonGroupId
    ) {
        return ApiResponse.ok("Addons found", menuAdminService.listAddons(cafeteriaId, addonGroupId));
    }

    @PostMapping("/addon-groups/{addonGroupId}/addons")
    public ApiResponse<Map<String, Object>> createAddon(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonGroupId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Addon created successfully", menuAdminService.createAddon(cafeteriaId, addonGroupId, request));
    }

    @PutMapping("/addon-groups/{addonGroupId}/addons/{addonId}")
    public ApiResponse<Map<String, Object>> updateAddon(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonGroupId,
            @PathVariable Long addonId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Addon updated successfully", menuAdminService.updateAddon(cafeteriaId, addonGroupId, addonId, request));
    }

    @DeleteMapping("/addon-groups/{addonGroupId}/addons/{addonId}")
    public ApiResponse<Void> deleteAddon(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonGroupId,
            @PathVariable Long addonId
    ) {
        menuAdminService.softDeleteAddon(cafeteriaId, addonGroupId, addonId);
        return ApiResponse.ok("Addon deleted successfully", null);
    }

    @GetMapping("/addons/{addonId}/translations")
    public ApiResponse<List<Map<String, Object>>> listAddonTranslations(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonId
    ) {
        return ApiResponse.ok("Addon translations found", menuAdminService.listAddonTranslations(cafeteriaId, addonId));
    }

    @PutMapping("/addons/{addonId}/translations/{languageCode}")
    public ApiResponse<Map<String, Object>> upsertAddonTranslation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonId,
            @PathVariable String languageCode,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Addon translation saved successfully", menuAdminService.upsertAddonTranslation(cafeteriaId, addonId, languageCode, request));
    }

    @DeleteMapping("/addons/{addonId}/translations/{languageCode}")
    public ApiResponse<Void> deleteAddonTranslation(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonId,
            @PathVariable String languageCode
    ) {
        menuAdminService.softDeleteAddonTranslation(cafeteriaId, addonId, languageCode);
        return ApiResponse.ok("Addon translation deleted successfully", null);
    }

    @GetMapping("/addons/{addonId}/prices")
    public ApiResponse<List<Map<String, Object>>> listAddonPrices(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonId
    ) {
        return ApiResponse.ok("Addon prices found", menuAdminService.listAddonPrices(cafeteriaId, addonId));
    }

    @PutMapping("/addons/{addonId}/prices/{currencyCode}")
    public ApiResponse<Map<String, Object>> upsertAddonPrice(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonId,
            @PathVariable String currencyCode,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok("Addon price saved successfully", menuAdminService.upsertAddonPrice(cafeteriaId, addonId, currencyCode, request));
    }

    @DeleteMapping("/addons/{addonId}/prices/{addonPriceId}")
    public ApiResponse<Void> deleteAddonPrice(
            @PathVariable Long cafeteriaId,
            @PathVariable Long addonId,
            @PathVariable Long addonPriceId
    ) {
        menuAdminService.softDeleteAddonPrice(cafeteriaId, addonId, addonPriceId);
        return ApiResponse.ok("Addon price deleted successfully", null);
    }

    @PutMapping("/locations/{locationId}/addons/{addonId}")
    public ApiResponse<Map<String, Object>> configureLocationAddon(
            @PathVariable Long cafeteriaId,
            @PathVariable Long locationId,
            @PathVariable Long addonId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok(
                "Location addon configured successfully",
                menuAdminService.configureLocationAddon(cafeteriaId, locationId, addonId, request)
        );
    }

    @PostMapping("/products/{productId}/addon-groups")
    public ApiResponse<Map<String, Object>> attachProductAddonGroup(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @RequestBody Map<String, Object> request
    ) {
        return ApiResponse.ok(
                "Product addon group attached successfully",
                menuAdminService.attachProductAddonGroup(cafeteriaId, productId, request)
        );
    }

    @DeleteMapping("/products/{productId}/addon-groups/{addonGroupId}")
    public ApiResponse<Void> detachProductAddonGroup(
            @PathVariable Long cafeteriaId,
            @PathVariable Long productId,
            @PathVariable Long addonGroupId
    ) {
        menuAdminService.detachProductAddonGroup(cafeteriaId, productId, addonGroupId);
        return ApiResponse.ok("Product addon group detached successfully", null);
    }
}
