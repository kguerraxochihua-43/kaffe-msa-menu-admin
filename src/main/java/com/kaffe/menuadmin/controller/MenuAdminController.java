package com.kaffe.menuadmin.controller;

import com.kaffe.common.web.ApiResponse;
import com.kaffe.menuadmin.service.MenuAdminService;
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

@RestController
@RequestMapping("/api/menu-admin/cafeterias/{cafeteriaId}")
@RequiredArgsConstructor
public class MenuAdminController {

    private final MenuAdminService menuAdminService;

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
