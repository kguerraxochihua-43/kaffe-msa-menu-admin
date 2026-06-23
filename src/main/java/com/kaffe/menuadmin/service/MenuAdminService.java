package com.kaffe.menuadmin.service;

import com.kaffe.common.exception.BadRequestException;
import com.kaffe.common.exception.ForbiddenException;
import com.kaffe.common.exception.ResourceNotFoundException;
import com.kaffe.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MenuAdminService {

    private static final List<String> MANAGER_ROLES = List.of("owner", "admin");

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUserProvider;

    @org.springframework.beans.factory.annotation.Value("${kaffe.auth.schema:kaffe_auth}")
    private String authSchema = "auth";

    public List<Map<String, Object>> listMenus(Long cafeteriaId) {
        requireTenantAccess(cafeteriaId, false);
        return jdbcTemplate.query("""
                        select *
                        from menu.menus
                        where tenant_id = ?
                          and deleted_at is null
                        order by sort_order asc, name asc
                        """,
                (rs, rowNum) -> menuRow(rs),
                cafeteriaId);
    }

    public Map<String, Object> getMenu(Long cafeteriaId, Long menuId) {
        requireTenantAccess(cafeteriaId, false);
        Map<String, Object> menu = findMenu(cafeteriaId, menuId);
        menu.put("locations", menuLocations(menuId));
        menu.put("categories", menuCategories(cafeteriaId, menuId));
        return menu;
    }

    @Transactional
    public Map<String, Object> createMenu(Long cafeteriaId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        Long menuId = jdbcTemplate.queryForObject("""
                        insert into menu.menus (
                            tenant_id, name, description, is_global, is_active, sort_order
                        )
                        values (?, ?, ?, ?, ?, ?)
                        returning menu_id
                        """,
                Long.class,
                cafeteriaId,
                requiredString(request, "name"),
                stringOrNull(request.get("description")),
                booleanValue(request.getOrDefault("global", true)),
                booleanValue(request.getOrDefault("active", true)),
                intValue(request.getOrDefault("sortOrder", 0)));

        updateMenuLocations(cafeteriaId, menuId, request);
        if (request.containsKey("categoryIds")) {
            replaceMenuCategories(cafeteriaId, menuId, longList(request.get("categoryIds")));
        }
        return getMenu(cafeteriaId, menuId);
    }

    @Transactional
    public Map<String, Object> updateMenu(Long cafeteriaId, Long menuId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureMenu(cafeteriaId, menuId);
        jdbcTemplate.update("""
                        update menu.menus
                        set name = coalesce(?, name),
                            description = coalesce(?, description),
                            is_global = coalesce(?, is_global),
                            is_active = coalesce(?, is_active),
                            sort_order = coalesce(?, sort_order),
                            updated_at = now()
                        where tenant_id = ?
                          and menu_id = ?
                          and deleted_at is null
                        """,
                stringOrNull(request.get("name")),
                stringOrNull(request.get("description")),
                nullableBoolean(request.get("global")),
                nullableBoolean(request.get("active")),
                nullableInt(request.get("sortOrder")),
                cafeteriaId,
                menuId);

        if (request.containsKey("locationIds") || request.containsKey("global")) {
            updateMenuLocations(cafeteriaId, menuId, request);
        }
        if (request.containsKey("categoryIds")) {
            replaceMenuCategories(cafeteriaId, menuId, longList(request.get("categoryIds")));
        }
        return getMenu(cafeteriaId, menuId);
    }

    @Transactional
    public void softDeleteMenu(Long cafeteriaId, Long menuId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        int updated = jdbcTemplate.update("""
                        update menu.menus
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and menu_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                menuId);
        if (updated == 0) {
            throw new ResourceNotFoundException("Menu was not found for this cafeteria");
        }
        softDeleteMenuJoins(cafeteriaId, menuId, userId);
    }

    public List<Map<String, Object>> listCategories(Long cafeteriaId) {
        requireTenantAccess(cafeteriaId, false);
        return jdbcTemplate.query("""
                        select *
                        from menu.categories
                        where tenant_id = ?
                          and deleted_at is null
                        order by sort_order asc, name asc
                        """,
                (rs, rowNum) -> categoryRow(rs),
                cafeteriaId);
    }

    public Map<String, Object> getCategory(Long cafeteriaId, Long categoryId) {
        requireTenantAccess(cafeteriaId, false);
        return findCategory(cafeteriaId, categoryId);
    }

    @Transactional
    public Map<String, Object> createCategory(Long cafeteriaId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        Long categoryId = jdbcTemplate.queryForObject("""
                        insert into menu.categories (tenant_id, name, description, sort_order, is_active)
                        values (?, ?, ?, ?, ?)
                        returning category_id
                        """,
                Long.class,
                cafeteriaId,
                requiredString(request, "name"),
                stringOrNull(request.get("description")),
                intValue(request.getOrDefault("sortOrder", 0)),
                booleanValue(request.getOrDefault("active", true)));

        if (request.containsKey("menuIds")) {
            replaceCategoryMenus(cafeteriaId, categoryId, longList(request.get("menuIds")));
        }
        return findCategory(cafeteriaId, categoryId);
    }

    @Transactional
    public Map<String, Object> updateCategory(Long cafeteriaId, Long categoryId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureCategory(cafeteriaId, categoryId);
        jdbcTemplate.update("""
                        update menu.categories
                        set name = coalesce(?, name),
                            description = coalesce(?, description),
                            sort_order = coalesce(?, sort_order),
                            is_active = coalesce(?, is_active),
                            updated_at = now()
                        where tenant_id = ?
                          and category_id = ?
                          and deleted_at is null
                        """,
                stringOrNull(request.get("name")),
                stringOrNull(request.get("description")),
                nullableInt(request.get("sortOrder")),
                nullableBoolean(request.get("active")),
                cafeteriaId,
                categoryId);
        if (request.containsKey("menuIds")) {
            replaceCategoryMenus(cafeteriaId, categoryId, longList(request.get("menuIds")));
        }
        return findCategory(cafeteriaId, categoryId);
    }

    @Transactional
    public void softDeleteCategory(Long cafeteriaId, Long categoryId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureCategory(cafeteriaId, categoryId);
        jdbcTemplate.update("""
                        update menu.categories
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and category_id = ?
                        """,
                userId,
                cafeteriaId,
                categoryId);
        jdbcTemplate.update("""
                        update menu.menu_categories
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and category_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                categoryId);
        jdbcTemplate.update("""
                        update menu.products
                        set category_id = null,
                            updated_at = now()
                        where tenant_id = ?
                          and category_id = ?
                          and deleted_at is null
                        """,
                cafeteriaId,
                categoryId);
    }

    public List<Map<String, Object>> listProducts(Long cafeteriaId, Long categoryId) {
        requireTenantAccess(cafeteriaId, false);
        List<Object> params = new ArrayList<>();
        params.add(cafeteriaId);
        if (categoryId != null) {
            params.add(categoryId);
        }
        return jdbcTemplate.query("""
                        select p.*, c.name as category_name
                        from menu.products p
                        left join menu.categories c on c.category_id = p.category_id
                        where p.tenant_id = ?
                          and p.deleted_at is null
                          %s
                        order by c.sort_order nulls last, p.sort_order asc, p.name asc
                        """.formatted(categoryId == null ? "" : "and p.category_id = ?"),
                (rs, rowNum) -> productRow(rs),
                params.toArray());
    }

    public Map<String, Object> getProduct(Long cafeteriaId, Long productId) {
        requireTenantAccess(cafeteriaId, false);
        Map<String, Object> product = findProduct(cafeteriaId, productId);
        product.put("addonGroups", productAddonGroups(cafeteriaId, productId));
        product.put("locations", productLocations(productId));
        return product;
    }

    @Transactional
    public Map<String, Object> createProduct(Long cafeteriaId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        Long categoryId = nullableLong(request.get("categoryId"));
        if (categoryId != null) {
            ensureCategory(cafeteriaId, categoryId);
        }
        Long productId = jdbcTemplate.queryForObject("""
                        insert into menu.products (
                            tenant_id, category_id, name, description, base_price,
                            image_url, is_featured, is_available, sort_order
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        returning product_id
                        """,
                Long.class,
                cafeteriaId,
                categoryId,
                requiredString(request, "name"),
                stringOrNull(request.get("description")),
                intValue(request.getOrDefault("basePrice", 0)),
                stringOrNull(request.get("imageUrl")),
                booleanValue(request.getOrDefault("featured", false)),
                booleanValue(request.getOrDefault("available", true)),
                intValue(request.getOrDefault("sortOrder", 0)));

        if (request.containsKey("addonGroupIds")) {
            replaceProductAddonGroups(cafeteriaId, productId, longList(request.get("addonGroupIds")));
        }
        if (request.containsKey("locationIds")) {
            updateProductLocations(cafeteriaId, productId, request);
        }
        return getProduct(cafeteriaId, productId);
    }

    @Transactional
    public Map<String, Object> updateProduct(Long cafeteriaId, Long productId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureProduct(cafeteriaId, productId);
        Long categoryId = nullableLong(request.get("categoryId"));
        if (categoryId != null) {
            ensureCategory(cafeteriaId, categoryId);
        }
        jdbcTemplate.update("""
                        update menu.products
                        set category_id = coalesce(?, category_id),
                            name = coalesce(?, name),
                            description = coalesce(?, description),
                            base_price = coalesce(?, base_price),
                            image_url = coalesce(?, image_url),
                            is_featured = coalesce(?, is_featured),
                            is_available = coalesce(?, is_available),
                            sort_order = coalesce(?, sort_order),
                            updated_at = now()
                        where tenant_id = ?
                          and product_id = ?
                          and deleted_at is null
                        """,
                categoryId,
                stringOrNull(request.get("name")),
                stringOrNull(request.get("description")),
                nullableInt(request.get("basePrice")),
                stringOrNull(request.get("imageUrl")),
                nullableBoolean(request.get("featured")),
                nullableBoolean(request.get("available")),
                nullableInt(request.get("sortOrder")),
                cafeteriaId,
                productId);
        if (request.containsKey("addonGroupIds")) {
            replaceProductAddonGroups(cafeteriaId, productId, longList(request.get("addonGroupIds")));
        }
        if (request.containsKey("locationIds") || request.containsKey("global")) {
            updateProductLocations(cafeteriaId, productId, request);
        }
        return getProduct(cafeteriaId, productId);
    }

    @Transactional
    public void softDeleteProduct(Long cafeteriaId, Long productId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureProduct(cafeteriaId, productId);
        jdbcTemplate.update("""
                        update menu.products
                        set is_available = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and product_id = ?
                        """,
                userId,
                cafeteriaId,
                productId);
        jdbcTemplate.update("""
                        update menu.location_products
                        set is_available = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and product_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                productId);
        jdbcTemplate.update("""
                        update menu.product_addon_groups
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and product_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                productId);
    }

    public List<Map<String, Object>> listAddonGroups(Long cafeteriaId) {
        requireTenantAccess(cafeteriaId, false);
        return jdbcTemplate.query("""
                        select *
                        from menu.lkp_addon_groups
                        where tenant_id = ?
                          and deleted_at is null
                        order by sort_order asc, name asc
                        """,
                (rs, rowNum) -> addonGroupRow(rs),
                cafeteriaId);
    }

    public Map<String, Object> getAddonGroup(Long cafeteriaId, Long addonGroupId) {
        requireTenantAccess(cafeteriaId, false);
        Map<String, Object> group = findAddonGroup(cafeteriaId, addonGroupId);
        group.put("addons", listAddons(cafeteriaId, addonGroupId));
        return group;
    }

    @Transactional
    public Map<String, Object> createAddonGroup(Long cafeteriaId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        validateSelection(request);
        Long addonGroupId = jdbcTemplate.queryForObject("""
                        insert into menu.lkp_addon_groups (
                            tenant_id, name, description, min_selection, max_selection,
                            is_required, sort_order, is_active
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        returning addon_group_id
                        """,
                Long.class,
                cafeteriaId,
                requiredString(request, "name"),
                stringOrNull(request.get("description")),
                intValue(request.getOrDefault("minSelection", 0)),
                nullableInt(request.get("maxSelection")),
                booleanValue(request.getOrDefault("required", false)),
                intValue(request.getOrDefault("sortOrder", 0)),
                booleanValue(request.getOrDefault("active", true)));
        return getAddonGroup(cafeteriaId, addonGroupId);
    }

    @Transactional
    public Map<String, Object> updateAddonGroup(Long cafeteriaId, Long addonGroupId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureAddonGroup(cafeteriaId, addonGroupId);
        validateSelection(request);
        jdbcTemplate.update("""
                        update menu.lkp_addon_groups
                        set name = coalesce(?, name),
                            description = coalesce(?, description),
                            min_selection = coalesce(?, min_selection),
                            max_selection = coalesce(?, max_selection),
                            is_required = coalesce(?, is_required),
                            sort_order = coalesce(?, sort_order),
                            is_active = coalesce(?, is_active),
                            updated_at = now()
                        where tenant_id = ?
                          and addon_group_id = ?
                          and deleted_at is null
                        """,
                stringOrNull(request.get("name")),
                stringOrNull(request.get("description")),
                nullableInt(request.get("minSelection")),
                nullableInt(request.get("maxSelection")),
                nullableBoolean(request.get("required")),
                nullableInt(request.get("sortOrder")),
                nullableBoolean(request.get("active")),
                cafeteriaId,
                addonGroupId);
        return getAddonGroup(cafeteriaId, addonGroupId);
    }

    @Transactional
    public void softDeleteAddonGroup(Long cafeteriaId, Long addonGroupId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureAddonGroup(cafeteriaId, addonGroupId);
        jdbcTemplate.update("""
                        update menu.lkp_addon_groups
                        set name = concat('deleted__', addon_group_id, '__', name),
                            is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and addon_group_id = ?
                        """,
                userId,
                cafeteriaId,
                addonGroupId);
        jdbcTemplate.update("""
                        update menu.lkp_addons
                        set name = concat('deleted__', addon_id, '__', name),
                            is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and addon_group_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                addonGroupId);
        jdbcTemplate.update("""
                        update menu.product_addon_groups
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and addon_group_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                addonGroupId);
    }

    public List<Map<String, Object>> listAddons(Long cafeteriaId, Long addonGroupId) {
        requireTenantAccess(cafeteriaId, false);
        if (addonGroupId != null) {
            ensureAddonGroup(cafeteriaId, addonGroupId);
        }
        List<Object> params = new ArrayList<>();
        params.add(cafeteriaId);
        if (addonGroupId != null) {
            params.add(addonGroupId);
        }
        return jdbcTemplate.query("""
                        select a.*, g.name as addon_group_name
                        from menu.lkp_addons a
                        join menu.lkp_addon_groups g on g.addon_group_id = a.addon_group_id
                        where a.tenant_id = ?
                          and a.deleted_at is null
                          %s
                        order by g.sort_order asc, a.sort_order asc, a.name asc
                        """.formatted(addonGroupId == null ? "" : "and a.addon_group_id = ?"),
                (rs, rowNum) -> addonRow(rs),
                params.toArray());
    }

    @Transactional
    public Map<String, Object> createAddon(Long cafeteriaId, Long addonGroupId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureAddonGroup(cafeteriaId, addonGroupId);
        Long addonId = jdbcTemplate.queryForObject("""
                        insert into menu.lkp_addons (
                            tenant_id, addon_group_id, name, description, price, sort_order, is_active
                        )
                        values (?, ?, ?, ?, ?, ?, ?)
                        returning addon_id
                        """,
                Long.class,
                cafeteriaId,
                addonGroupId,
                requiredString(request, "name"),
                stringOrNull(request.get("description")),
                intValue(request.getOrDefault("price", 0)),
                intValue(request.getOrDefault("sortOrder", 0)),
                booleanValue(request.getOrDefault("active", true)));
        return findAddon(cafeteriaId, addonId);
    }

    @Transactional
    public Map<String, Object> updateAddon(Long cafeteriaId, Long addonGroupId, Long addonId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureAddon(cafeteriaId, addonGroupId, addonId);
        jdbcTemplate.update("""
                        update menu.lkp_addons
                        set name = coalesce(?, name),
                            description = coalesce(?, description),
                            price = coalesce(?, price),
                            sort_order = coalesce(?, sort_order),
                            is_active = coalesce(?, is_active),
                            updated_at = now()
                        where tenant_id = ?
                          and addon_group_id = ?
                          and addon_id = ?
                          and deleted_at is null
                        """,
                stringOrNull(request.get("name")),
                stringOrNull(request.get("description")),
                nullableInt(request.get("price")),
                nullableInt(request.get("sortOrder")),
                nullableBoolean(request.get("active")),
                cafeteriaId,
                addonGroupId,
                addonId);
        return findAddon(cafeteriaId, addonId);
    }

    @Transactional
    public void softDeleteAddon(Long cafeteriaId, Long addonGroupId, Long addonId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureAddon(cafeteriaId, addonGroupId, addonId);
        jdbcTemplate.update("""
                        update menu.lkp_addons
                        set name = concat('deleted__', addon_id, '__', name),
                            is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and addon_group_id = ?
                          and addon_id = ?
                        """,
                userId,
                cafeteriaId,
                addonGroupId,
                addonId);
        jdbcTemplate.update("""
                        update menu.location_addons
                        set is_available = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and addon_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                addonId);
    }

    @Transactional
    public Map<String, Object> configureLocationProduct(Long cafeteriaId, Long locationId, Long productId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureLocation(cafeteriaId, locationId);
        ensureProduct(cafeteriaId, productId);
        Long locationProductId = jdbcTemplate.queryForObject("""
                        insert into menu.location_products (
                            location_id, tenant_id, product_id, price_override,
                            is_available, is_featured, sort_order, deleted_at, deleted_by_user_id
                        )
                        values (?, ?, ?, ?, ?, ?, ?, null, null)
                        on conflict (location_id, product_id) do update set
                            price_override = excluded.price_override,
                            is_available = excluded.is_available,
                            is_featured = excluded.is_featured,
                            sort_order = excluded.sort_order,
                            deleted_at = null,
                            deleted_by_user_id = null,
                            updated_at = now()
                        returning location_product_id
                        """,
                Long.class,
                locationId,
                cafeteriaId,
                productId,
                nullableInt(request.get("priceOverride")),
                booleanValue(request.getOrDefault("available", true)),
                booleanValue(request.getOrDefault("featured", false)),
                intValue(request.getOrDefault("sortOrder", 0)));
        return findLocationProduct(locationProductId);
    }

    @Transactional
    public List<Map<String, Object>> assignProductLocations(Long cafeteriaId, Long productId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureProduct(cafeteriaId, productId);
        updateProductLocations(cafeteriaId, productId, request);
        return productLocations(productId);
    }

    @Transactional
    public Map<String, Object> configureLocationAddon(Long cafeteriaId, Long locationId, Long addonId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureLocation(cafeteriaId, locationId);
        ensureAddon(cafeteriaId, addonId);
        Long locationAddonId = jdbcTemplate.queryForObject("""
                        insert into menu.location_addons (
                            location_id, tenant_id, addon_id, is_available, price_override,
                            deleted_at, deleted_by_user_id
                        )
                        values (?, ?, ?, ?, ?, null, null)
                        on conflict (location_id, addon_id) do update set
                            is_available = excluded.is_available,
                            price_override = excluded.price_override,
                            deleted_at = null,
                            deleted_by_user_id = null,
                            updated_at = now()
                        returning location_addon_id
                        """,
                Long.class,
                locationId,
                cafeteriaId,
                addonId,
                booleanValue(request.getOrDefault("available", true)),
                nullableInt(request.get("priceOverride")));
        return findLocationAddon(locationAddonId);
    }

    @Transactional
    public Map<String, Object> attachProductAddonGroup(Long cafeteriaId, Long productId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureProduct(cafeteriaId, productId);
        Long addonGroupId = requiredLong(request, "addonGroupId");
        Map<String, Object> addonGroup = findAddonGroup(cafeteriaId, addonGroupId);
        Boolean required = nullableBoolean(request.get("required"));
        Integer minSelection = nullableInt(request.get("minSelection"));
        Integer maxSelection = nullableInt(request.get("maxSelection"));
        if (required == null) {
            required = (Boolean) addonGroup.get("required");
        }
        if (minSelection == null) {
            minSelection = (Integer) addonGroup.get("minSelection");
        }
        if (maxSelection == null) {
            maxSelection = (Integer) addonGroup.get("maxSelection");
        }
        validateSelection(minSelection, maxSelection);
        jdbcTemplate.update("""
                        insert into menu.product_addon_groups (
                            product_id, tenant_id, addon_group_id, sort_order, is_active,
                            is_required, min_selection, max_selection, deleted_at, deleted_by_user_id
                        )
                        values (?, ?, ?, ?, true, ?, ?, ?, null, null)
                        on conflict (product_id, addon_group_id) do update set
                            sort_order = excluded.sort_order,
                            is_active = true,
                            is_required = excluded.is_required,
                            min_selection = excluded.min_selection,
                            max_selection = excluded.max_selection,
                            deleted_at = null,
                            deleted_by_user_id = null,
                            updated_at = now()
                        """,
                productId,
                cafeteriaId,
                addonGroupId,
                intValue(request.getOrDefault("sortOrder", 0)),
                required,
                minSelection,
                maxSelection);
        return findProductAddonGroup(cafeteriaId, productId, addonGroupId);
    }

    @Transactional
    public void detachProductAddonGroup(Long cafeteriaId, Long productId, Long addonGroupId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureProduct(cafeteriaId, productId);
        ensureAddonGroup(cafeteriaId, addonGroupId);
        int updated = jdbcTemplate.update("""
                        update menu.product_addon_groups
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and product_id = ?
                          and addon_group_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                productId,
                addonGroupId);
        if (updated == 0) {
            throw new ResourceNotFoundException("Product addon group was not found");
        }
    }

    private void updateMenuLocations(Long cafeteriaId, Long menuId, Map<String, Object> request) {
        boolean global = booleanValue(request.getOrDefault("global", true));
        jdbcTemplate.update("update menu.menus set is_global = ?, updated_at = now() where tenant_id = ? and menu_id = ?", global, cafeteriaId, menuId);
        if (global) {
            softDeleteMenuLocations(cafeteriaId, menuId, currentUserProvider.requireUserId());
            return;
        }

        List<Long> locationIds = longList(request.get("locationIds"));
        if (locationIds.isEmpty()) {
            throw new BadRequestException("locationIds is required when global is false");
        }
        for (Long locationId : locationIds) {
            ensureLocation(cafeteriaId, locationId);
            jdbcTemplate.update("""
                            insert into menu.menu_locations (
                                menu_id, tenant_id, location_id, is_active, deleted_at, deleted_by_user_id
                            )
                            values (?, ?, ?, true, null, null)
                            on conflict (menu_id, location_id) do update set
                                is_active = true,
                                deleted_at = null,
                                deleted_by_user_id = null,
                                updated_at = now()
                            """,
                    menuId,
                    cafeteriaId,
                    locationId);
        }
        deactivateRemovedMenuLocations(cafeteriaId, menuId, locationIds);
    }

    private void updateProductLocations(Long cafeteriaId, Long productId, Map<String, Object> request) {
        boolean global = booleanValue(request.getOrDefault("global", false));
        if (global) {
            jdbcTemplate.update("""
                            update menu.location_products
                            set is_available = false,
                                deleted_at = now(),
                                deleted_by_user_id = ?,
                                updated_at = now()
                            where tenant_id = ?
                              and product_id = ?
                              and deleted_at is null
                            """,
                    currentUserProvider.requireUserId(),
                    cafeteriaId,
                    productId);
            return;
        }
        List<Long> locationIds = longList(request.get("locationIds"));
        if (locationIds.isEmpty()) {
            throw new BadRequestException("locationIds is required");
        }
        for (Long locationId : locationIds) {
            configureLocationProduct(cafeteriaId, locationId, productId, request);
        }
        deactivateRemovedProductLocations(cafeteriaId, productId, locationIds);
    }

    private void replaceMenuCategories(Long cafeteriaId, Long menuId, List<Long> categoryIds) {
        for (int i = 0; i < categoryIds.size(); i++) {
            Long categoryId = categoryIds.get(i);
            ensureCategory(cafeteriaId, categoryId);
            jdbcTemplate.update("""
                            insert into menu.menu_categories (
                                menu_id, tenant_id, category_id, sort_order, is_active, deleted_at, deleted_by_user_id
                            )
                            values (?, ?, ?, ?, true, null, null)
                            on conflict (menu_id, category_id) do update set
                                sort_order = excluded.sort_order,
                                is_active = true,
                                deleted_at = null,
                                deleted_by_user_id = null,
                                updated_at = now()
                            """,
                    menuId,
                    cafeteriaId,
                    categoryId,
                    i + 1);
        }
        deactivateRemovedMenuCategories(cafeteriaId, menuId, categoryIds);
    }

    private void replaceCategoryMenus(Long cafeteriaId, Long categoryId, List<Long> menuIds) {
        for (Long menuId : menuIds) {
            ensureMenu(cafeteriaId, menuId);
            jdbcTemplate.update("""
                            insert into menu.menu_categories (
                                menu_id, tenant_id, category_id, sort_order, is_active, deleted_at, deleted_by_user_id
                            )
                            values (?, ?, ?, 0, true, null, null)
                            on conflict (menu_id, category_id) do update set
                                is_active = true,
                                deleted_at = null,
                                deleted_by_user_id = null,
                                updated_at = now()
                            """,
                    menuId,
                    cafeteriaId,
                    categoryId);
        }
        deactivateRemovedCategoryMenus(cafeteriaId, categoryId, menuIds);
    }

    private void replaceProductAddonGroups(Long cafeteriaId, Long productId, List<Long> addonGroupIds) {
        for (Long addonGroupId : addonGroupIds) {
            attachProductAddonGroup(cafeteriaId, productId, Map.of("addonGroupId", addonGroupId));
        }
        deactivateRemovedProductAddonGroups(cafeteriaId, productId, addonGroupIds);
    }

    private void deactivateRemovedMenuLocations(Long cafeteriaId, Long menuId, List<Long> keepLocationIds) {
        List<Long> activeLocationIds = jdbcTemplate.queryForList("""
                        select location_id
                        from menu.menu_locations
                        where tenant_id = ?
                          and menu_id = ?
                          and deleted_at is null
                        """,
                Long.class,
                cafeteriaId,
                menuId);
        Set<Long> keep = new HashSet<>(keepLocationIds);
        for (Long locationId : activeLocationIds) {
            if (!keep.contains(locationId)) {
                jdbcTemplate.update("""
                                update menu.menu_locations
                                set is_active = false,
                                    deleted_at = now(),
                                    deleted_by_user_id = ?,
                                    updated_at = now()
                                where tenant_id = ?
                                  and menu_id = ?
                                  and location_id = ?
                                  and deleted_at is null
                                """,
                        currentUserProvider.requireUserId(),
                        cafeteriaId,
                        menuId,
                        locationId);
            }
        }
    }

    private void deactivateRemovedProductLocations(Long cafeteriaId, Long productId, List<Long> keepLocationIds) {
        List<Long> activeLocationIds = jdbcTemplate.queryForList("""
                        select location_id
                        from menu.location_products
                        where tenant_id = ?
                          and product_id = ?
                          and deleted_at is null
                        """,
                Long.class,
                cafeteriaId,
                productId);
        Set<Long> keep = new HashSet<>(keepLocationIds);
        for (Long locationId : activeLocationIds) {
            if (!keep.contains(locationId)) {
                jdbcTemplate.update("""
                                update menu.location_products
                                set is_available = false,
                                    deleted_at = now(),
                                    deleted_by_user_id = ?,
                                    updated_at = now()
                                where tenant_id = ?
                                  and product_id = ?
                                  and location_id = ?
                                  and deleted_at is null
                                """,
                        currentUserProvider.requireUserId(),
                        cafeteriaId,
                        productId,
                        locationId);
            }
        }
    }

    private void deactivateRemovedMenuCategories(Long cafeteriaId, Long menuId, List<Long> keepCategoryIds) {
        List<Long> activeCategoryIds = jdbcTemplate.queryForList("""
                        select category_id
                        from menu.menu_categories
                        where tenant_id = ?
                          and menu_id = ?
                          and deleted_at is null
                        """,
                Long.class,
                cafeteriaId,
                menuId);
        Set<Long> keep = new HashSet<>(keepCategoryIds);
        for (Long categoryId : activeCategoryIds) {
            if (!keep.contains(categoryId)) {
                softDeleteMenuCategory(cafeteriaId, menuId, categoryId);
            }
        }
    }

    private void deactivateRemovedCategoryMenus(Long cafeteriaId, Long categoryId, List<Long> keepMenuIds) {
        List<Long> activeMenuIds = jdbcTemplate.queryForList("""
                        select menu_id
                        from menu.menu_categories
                        where tenant_id = ?
                          and category_id = ?
                          and deleted_at is null
                        """,
                Long.class,
                cafeteriaId,
                categoryId);
        Set<Long> keep = new HashSet<>(keepMenuIds);
        for (Long menuId : activeMenuIds) {
            if (!keep.contains(menuId)) {
                softDeleteMenuCategory(cafeteriaId, menuId, categoryId);
            }
        }
    }

    private void deactivateRemovedProductAddonGroups(Long cafeteriaId, Long productId, List<Long> keepAddonGroupIds) {
        List<Long> activeAddonGroupIds = jdbcTemplate.queryForList("""
                        select addon_group_id
                        from menu.product_addon_groups
                        where tenant_id = ?
                          and product_id = ?
                          and deleted_at is null
                        """,
                Long.class,
                cafeteriaId,
                productId);
        Set<Long> keep = new HashSet<>(keepAddonGroupIds);
        for (Long addonGroupId : activeAddonGroupIds) {
            if (!keep.contains(addonGroupId)) {
                jdbcTemplate.update("""
                                update menu.product_addon_groups
                                set is_active = false,
                                    deleted_at = now(),
                                    deleted_by_user_id = ?,
                                    updated_at = now()
                                where tenant_id = ?
                                  and product_id = ?
                                  and addon_group_id = ?
                                  and deleted_at is null
                                """,
                        currentUserProvider.requireUserId(),
                        cafeteriaId,
                        productId,
                        addonGroupId);
            }
        }
    }

    private void softDeleteMenuCategory(Long cafeteriaId, Long menuId, Long categoryId) {
        jdbcTemplate.update("""
                        update menu.menu_categories
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and menu_id = ?
                          and category_id = ?
                          and deleted_at is null
                        """,
                currentUserProvider.requireUserId(),
                cafeteriaId,
                menuId,
                categoryId);
    }

    private Membership requireTenantAccess(Long cafeteriaId, boolean managerRequired) {
        Long userId = currentUserProvider.requireUserId();
        try {
            Membership membership = jdbcTemplate.queryForObject(authSql("""
                            select tu.user_id, tu.tenant_id, r.code as role_code
                            from auth.tenant_users tu
                            join catalog.lkp_roles r on r.role_id = tu.role_id
                            where tu.user_id = ?
                              and tu.tenant_id = ?
                            limit 1
                            """),
                    (rs, rowNum) -> new Membership(
                            rs.getLong("user_id"),
                            rs.getLong("tenant_id"),
                            rs.getString("role_code")
                    ),
                    userId,
                    cafeteriaId);
            if (managerRequired && !MANAGER_ROLES.contains(membership.roleCode())) {
                throw new ForbiddenException("User cannot manage menu for this cafeteria");
            }
            return membership;
        } catch (EmptyResultDataAccessException ex) {
            throw new ResourceNotFoundException("Cafeteria was not found for this user");
        }
    }

    private void ensureMenu(Long cafeteriaId, Long menuId) {
        ensureExists("select exists(select 1 from menu.menus where menu_id = ? and tenant_id = ? and deleted_at is null)", menuId, cafeteriaId, "Menu was not found for this cafeteria");
    }

    private void ensureCategory(Long cafeteriaId, Long categoryId) {
        ensureExists("select exists(select 1 from menu.categories where category_id = ? and tenant_id = ? and deleted_at is null)", categoryId, cafeteriaId, "Category was not found for this cafeteria");
    }

    private void ensureProduct(Long cafeteriaId, Long productId) {
        ensureExists("select exists(select 1 from menu.products where product_id = ? and tenant_id = ? and deleted_at is null)", productId, cafeteriaId, "Product was not found for this cafeteria");
    }

    private void ensureAddonGroup(Long cafeteriaId, Long addonGroupId) {
        ensureExists("select exists(select 1 from menu.lkp_addon_groups where addon_group_id = ? and tenant_id = ? and deleted_at is null)", addonGroupId, cafeteriaId, "Addon group was not found for this cafeteria");
    }

    private void ensureAddon(Long cafeteriaId, Long addonGroupId, Long addonId) {
        ensureExists("select exists(select 1 from menu.lkp_addons where addon_id = ? and tenant_id = ? and addon_group_id = ? and deleted_at is null)", addonId, cafeteriaId, addonGroupId, "Addon was not found for this group");
    }

    private void ensureAddon(Long cafeteriaId, Long addonId) {
        ensureExists("select exists(select 1 from menu.lkp_addons where addon_id = ? and tenant_id = ? and deleted_at is null)", addonId, cafeteriaId, "Addon was not found for this cafeteria");
    }

    private void ensureLocation(Long cafeteriaId, Long locationId) {
        ensureExists("select exists(select 1 from core.locations where location_id = ? and tenant_id = ? and status_id <> 4)", locationId, cafeteriaId, "Location was not found for this cafeteria");
    }

    private void ensureExists(String sql, Long id, Long cafeteriaId, String message) {
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, id, cafeteriaId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResourceNotFoundException(message);
        }
    }

    private void ensureExists(String sql, Long id, Long cafeteriaId, Long parentId, String message) {
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, id, cafeteriaId, parentId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResourceNotFoundException(message);
        }
    }

    private void softDeleteMenuJoins(Long cafeteriaId, Long menuId, Long userId) {
        softDeleteMenuLocations(cafeteriaId, menuId, userId);
        jdbcTemplate.update("""
                        update menu.menu_categories
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and menu_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                menuId);
    }

    private void softDeleteMenuLocations(Long cafeteriaId, Long menuId, Long userId) {
        jdbcTemplate.update("""
                        update menu.menu_locations
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and menu_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                menuId);
    }

    private Map<String, Object> findMenu(Long cafeteriaId, Long menuId) {
        return queryOne("""
                        select *
                        from menu.menus
                        where tenant_id = ?
                          and menu_id = ?
                          and deleted_at is null
                        """,
                rs -> menuRow(rs),
                cafeteriaId,
                menuId);
    }

    private Map<String, Object> findCategory(Long cafeteriaId, Long categoryId) {
        return queryOne("""
                        select *
                        from menu.categories
                        where tenant_id = ?
                          and category_id = ?
                          and deleted_at is null
                        """,
                rs -> categoryRow(rs),
                cafeteriaId,
                categoryId);
    }

    private Map<String, Object> findProduct(Long cafeteriaId, Long productId) {
        return queryOne("""
                        select p.*, c.name as category_name
                        from menu.products p
                        left join menu.categories c on c.category_id = p.category_id
                        where p.tenant_id = ?
                          and p.product_id = ?
                          and p.deleted_at is null
                        """,
                rs -> productRow(rs),
                cafeteriaId,
                productId);
    }

    private Map<String, Object> findAddonGroup(Long cafeteriaId, Long addonGroupId) {
        return queryOne("""
                        select *
                        from menu.lkp_addon_groups
                        where tenant_id = ?
                          and addon_group_id = ?
                          and deleted_at is null
                        """,
                rs -> addonGroupRow(rs),
                cafeteriaId,
                addonGroupId);
    }

    private Map<String, Object> findAddon(Long cafeteriaId, Long addonId) {
        return queryOne("""
                        select a.*, g.name as addon_group_name
                        from menu.lkp_addons a
                        join menu.lkp_addon_groups g on g.addon_group_id = a.addon_group_id
                        where a.tenant_id = ?
                          and a.addon_id = ?
                          and a.deleted_at is null
                        """,
                rs -> addonRow(rs),
                cafeteriaId,
                addonId);
    }

    private Map<String, Object> findProductAddonGroup(Long cafeteriaId, Long productId, Long addonGroupId) {
        return queryOne("""
                        select pag.*, g.name as addon_group_name
                        from menu.product_addon_groups pag
                        join menu.lkp_addon_groups g on g.addon_group_id = pag.addon_group_id
                        where pag.tenant_id = ?
                          and pag.product_id = ?
                          and pag.addon_group_id = ?
                          and pag.deleted_at is null
                        """,
                rs -> productAddonGroupRow(rs),
                cafeteriaId,
                productId,
                addonGroupId);
    }

    private Map<String, Object> findLocationProduct(Long locationProductId) {
        return queryOne("""
                        select lp.*, l.name as location_name, p.name as product_name
                        from menu.location_products lp
                        join core.locations l on l.location_id = lp.location_id
                        join menu.products p on p.product_id = lp.product_id
                        where lp.location_product_id = ?
                          and lp.deleted_at is null
                        """,
                rs -> locationProductRow(rs),
                locationProductId);
    }

    private Map<String, Object> findLocationAddon(Long locationAddonId) {
        return queryOne("""
                        select la.*, l.name as location_name, a.name as addon_name
                        from menu.location_addons la
                        join core.locations l on l.location_id = la.location_id
                        join menu.lkp_addons a on a.addon_id = la.addon_id
                        where la.location_addon_id = ?
                          and la.deleted_at is null
                        """,
                rs -> locationAddonRow(rs),
                locationAddonId);
    }

    private List<Map<String, Object>> menuLocations(Long menuId) {
        return jdbcTemplate.query("""
                        select ml.*, l.name as location_name, l.slug as location_slug
                        from menu.menu_locations ml
                        join core.locations l on l.location_id = ml.location_id
                        where ml.menu_id = ?
                          and ml.deleted_at is null
                        order by l.name asc
                        """,
                (rs, rowNum) -> row(
                        "locationId", rs.getLong("location_id"),
                        "locationName", rs.getString("location_name"),
                        "locationSlug", rs.getString("location_slug"),
                        "active", rs.getBoolean("is_active")
                ),
                menuId);
    }

    private List<Map<String, Object>> menuCategories(Long cafeteriaId, Long menuId) {
        return jdbcTemplate.query("""
                        select mc.*, c.name, c.description
                        from menu.menu_categories mc
                        join menu.categories c on c.category_id = mc.category_id
                        where mc.tenant_id = ?
                          and mc.menu_id = ?
                          and mc.deleted_at is null
                          and c.deleted_at is null
                        order by mc.sort_order asc, c.name asc
                        """,
                (rs, rowNum) -> row(
                        "categoryId", rs.getLong("category_id"),
                        "name", rs.getString("name"),
                        "description", rs.getString("description"),
                        "sortOrder", rs.getInt("sort_order"),
                        "active", rs.getBoolean("is_active")
                ),
                cafeteriaId,
                menuId);
    }

    private List<Map<String, Object>> productAddonGroups(Long cafeteriaId, Long productId) {
        return jdbcTemplate.query("""
                        select pag.*, g.name as addon_group_name
                        from menu.product_addon_groups pag
                        join menu.lkp_addon_groups g on g.addon_group_id = pag.addon_group_id
                        where pag.tenant_id = ?
                          and pag.product_id = ?
                          and pag.deleted_at is null
                          and g.deleted_at is null
                        order by pag.sort_order asc, g.name asc
                        """,
                (rs, rowNum) -> productAddonGroupRow(rs),
                cafeteriaId,
                productId);
    }

    private List<Map<String, Object>> productLocations(Long productId) {
        return jdbcTemplate.query("""
                        select lp.*, l.name as location_name, p.name as product_name
                        from menu.location_products lp
                        join core.locations l on l.location_id = lp.location_id
                        join menu.products p on p.product_id = lp.product_id
                        where lp.product_id = ?
                          and lp.deleted_at is null
                        order by l.name asc
                        """,
                (rs, rowNum) -> locationProductRow(rs),
                productId);
    }

    private Map<String, Object> menuRow(ResultSet rs) throws SQLException {
        return row(
                "menuId", rs.getLong("menu_id"),
                "cafeteriaId", rs.getLong("tenant_id"),
                "name", rs.getString("name"),
                "description", rs.getString("description"),
                "global", rs.getBoolean("is_global"),
                "active", rs.getBoolean("is_active"),
                "sortOrder", rs.getInt("sort_order"),
                "createdAt", rs.getTimestamp("created_at"),
                "updatedAt", rs.getTimestamp("updated_at")
        );
    }

    private Map<String, Object> categoryRow(ResultSet rs) throws SQLException {
        return row(
                "categoryId", rs.getLong("category_id"),
                "cafeteriaId", rs.getLong("tenant_id"),
                "name", rs.getString("name"),
                "description", rs.getString("description"),
                "sortOrder", rs.getInt("sort_order"),
                "active", rs.getBoolean("is_active")
        );
    }

    private Map<String, Object> productRow(ResultSet rs) throws SQLException {
        return row(
                "productId", rs.getLong("product_id"),
                "cafeteriaId", rs.getLong("tenant_id"),
                "categoryId", nullableColumnLong(rs, "category_id"),
                "categoryName", nullableColumnString(rs, "category_name"),
                "name", rs.getString("name"),
                "description", rs.getString("description"),
                "basePrice", rs.getInt("base_price"),
                "imageUrl", rs.getString("image_url"),
                "featured", rs.getBoolean("is_featured"),
                "available", rs.getBoolean("is_available"),
                "sortOrder", rs.getInt("sort_order")
        );
    }

    private Map<String, Object> addonGroupRow(ResultSet rs) throws SQLException {
        return row(
                "addonGroupId", rs.getLong("addon_group_id"),
                "cafeteriaId", rs.getLong("tenant_id"),
                "name", rs.getString("name"),
                "description", rs.getString("description"),
                "required", rs.getBoolean("is_required"),
                "minSelection", rs.getInt("min_selection"),
                "maxSelection", nullableColumnInt(rs, "max_selection"),
                "sortOrder", rs.getInt("sort_order"),
                "active", rs.getBoolean("is_active")
        );
    }

    private Map<String, Object> addonRow(ResultSet rs) throws SQLException {
        return row(
                "addonId", rs.getLong("addon_id"),
                "cafeteriaId", rs.getLong("tenant_id"),
                "addonGroupId", rs.getLong("addon_group_id"),
                "addonGroupName", nullableColumnString(rs, "addon_group_name"),
                "name", rs.getString("name"),
                "description", rs.getString("description"),
                "price", rs.getInt("price"),
                "sortOrder", rs.getInt("sort_order"),
                "active", rs.getBoolean("is_active")
        );
    }

    private Map<String, Object> productAddonGroupRow(ResultSet rs) throws SQLException {
        return row(
                "cafeteriaId", rs.getLong("tenant_id"),
                "productId", rs.getLong("product_id"),
                "addonGroupId", rs.getLong("addon_group_id"),
                "addonGroupName", nullableColumnString(rs, "addon_group_name"),
                "required", nullableColumnBoolean(rs, "is_required"),
                "minSelection", nullableColumnInt(rs, "min_selection"),
                "maxSelection", nullableColumnInt(rs, "max_selection"),
                "sortOrder", rs.getInt("sort_order"),
                "active", rs.getBoolean("is_active")
        );
    }

    private Map<String, Object> locationProductRow(ResultSet rs) throws SQLException {
        return row(
                "locationProductId", rs.getLong("location_product_id"),
                "cafeteriaId", rs.getLong("tenant_id"),
                "locationId", rs.getLong("location_id"),
                "locationName", nullableColumnString(rs, "location_name"),
                "productId", rs.getLong("product_id"),
                "productName", nullableColumnString(rs, "product_name"),
                "priceOverride", nullableColumnInt(rs, "price_override"),
                "available", rs.getBoolean("is_available"),
                "featured", rs.getBoolean("is_featured"),
                "sortOrder", rs.getInt("sort_order")
        );
    }

    private Map<String, Object> locationAddonRow(ResultSet rs) throws SQLException {
        return row(
                "locationAddonId", rs.getLong("location_addon_id"),
                "cafeteriaId", rs.getLong("tenant_id"),
                "locationId", rs.getLong("location_id"),
                "locationName", nullableColumnString(rs, "location_name"),
                "addonId", rs.getLong("addon_id"),
                "addonName", nullableColumnString(rs, "addon_name"),
                "available", rs.getBoolean("is_available"),
                "priceOverride", nullableColumnInt(rs, "price_override")
        );
    }

    private <T> T queryOne(String sql, RowMapperOne<T> mapper, Object... args) {
        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> mapper.map(rs), args);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResourceNotFoundException("Resource was not found");
        }
    }

    private void validateSelection(Map<String, Object> request) {
        Integer min = nullableInt(request.get("minSelection"));
        Integer max = nullableInt(request.get("maxSelection"));
        validateSelection(min, max);
    }

    private void validateSelection(Integer min, Integer max) {
        if (min != null && min < 0) {
            throw new BadRequestException("minSelection must be greater than or equal to zero");
        }
        if (max != null && max < 0) {
            throw new BadRequestException("maxSelection must be greater than or equal to zero");
        }
        if (min != null && max != null && max < min) {
            throw new BadRequestException("maxSelection must be greater than or equal to minSelection");
        }
    }

    private String requiredString(Map<String, Object> request, String key) {
        String value = stringOrNull(request.get(key));
        if (value == null) {
            throw new BadRequestException(key + " is required");
        }
        return value;
    }

    private Long requiredLong(Map<String, Object> request, String key) {
        Long value = nullableLong(request.get(key));
        if (value == null) {
            throw new BadRequestException(key + " is required");
        }
        return value;
    }

    private String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Integer nullableInt(Object value) {
        Long number = nullableLong(value);
        return number == null ? null : number.intValue();
    }

    private int intValue(Object value) {
        Integer number = nullableInt(value);
        if (number == null) {
            throw new BadRequestException("Numeric value is required");
        }
        return number;
    }

    private Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : Long.parseLong(text);
    }

    @SuppressWarnings("unchecked")
    private List<Long> longList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new BadRequestException("Expected a list of ids");
        }
        return values.stream().map(this::nullableLong).filter(id -> id != null).toList();
    }

    private Boolean nullableBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        Boolean bool = nullableBoolean(value);
        return bool != null && bool;
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            Object value = values[i + 1];
            if (value != null) {
                map.put(String.valueOf(values[i]), value);
            }
        }
        return map;
    }

    private Long nullableColumnLong(ResultSet rs, String column) throws SQLException {
        Object value = nullableColumn(rs, column);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.parseLong(String.valueOf(value));
    }

    private Integer nullableColumnInt(ResultSet rs, String column) throws SQLException {
        Object value = nullableColumn(rs, column);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.parseInt(String.valueOf(value));
    }

    private Boolean nullableColumnBoolean(ResultSet rs, String column) throws SQLException {
        Object value = nullableColumn(rs, column);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.parseBoolean(String.valueOf(value));
    }

    private String nullableColumnString(ResultSet rs, String column) throws SQLException {
        Object value = nullableColumn(rs, column);
        return value == null ? null : String.valueOf(value);
    }

    private Object nullableColumn(ResultSet rs, String column) throws SQLException {
        if (!hasColumn(rs, column)) {
            return null;
        }
        return rs.getObject(column);
    }

    private boolean hasColumn(ResultSet rs, String column) throws SQLException {
        var metaData = rs.getMetaData();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            if (column.equalsIgnoreCase(metaData.getColumnLabel(i))) {
                return true;
            }
        }
        return false;
    }

    private record Membership(Long userId, Long tenantId, String roleCode) {
    }

    private String authSql(String sql) {
        return sql.replace("auth.", com.kaffe.common.sql.SqlSchemas.schema(authSchema) + ".");
    }

    @FunctionalInterface
    private interface RowMapperOne<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
