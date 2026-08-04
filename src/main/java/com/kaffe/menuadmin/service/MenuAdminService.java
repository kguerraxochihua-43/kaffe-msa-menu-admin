package com.kaffe.menuadmin.service;

import com.kaffe.common.exception.BadRequestException;
import com.kaffe.common.exception.ForbiddenException;
import com.kaffe.common.exception.ResourceNotFoundException;
import com.kaffe.common.media.MediaAsset;
import com.kaffe.common.media.MediaAssetService;
import com.kaffe.common.media.MediaCompleteRequest;
import com.kaffe.common.media.MediaUploadPolicy;
import com.kaffe.common.media.MediaUploadRequest;
import com.kaffe.common.media.PresignedMediaUpload;
import com.kaffe.common.security.CurrentUserProvider;
import com.kaffe.menuadmin.dto.MenuCatalogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MenuAdminService {

    private static final String PURPOSE_MENU_PRODUCT_IMAGE = "menu-product-image";
    private static final String POLICY_MENU_READ = "menu:read";
    private static final String POLICY_MENU_WRITE = "menu:write";

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUserProvider;
    private final MediaAssetService mediaAssetService;

    @org.springframework.beans.factory.annotation.Value("${kaffe.auth.schema:kaffe_auth}")
    private String authSchema = "auth";

    @Transactional(readOnly = true)
    public MenuCatalogResponse getCatalog(Long cafeteriaId, Long locationId) {
        if (locationId == null) {
            requireTenantAccess(cafeteriaId, false);
        } else {
            requireLocationAccess(cafeteriaId, locationId, false);
        }

        List<Map<String, Object>> categories = jdbcTemplate.query("""
                        select *
                        from menu.categories
                        where tenant_id = ?
                          and deleted_at is null
                        order by sort_order asc, name asc
                        """,
                (rs, rowNum) -> categoryRow(rs),
                cafeteriaId);

        List<Map<String, Object>> products = jdbcTemplate.query("""
                        select p.*, c.name as category_name,
                               lp.location_product_id,
                               lp.price_override,
                               lp.is_available as location_available,
                               lp.is_featured as location_featured,
                               lp.sort_order as location_sort_order
                        from menu.products p
                        left join menu.categories c on c.category_id = p.category_id
                        left join menu.location_products lp
                          on lp.tenant_id = p.tenant_id
                         and lp.product_id = p.product_id
                         and lp.location_id = ?
                         and lp.deleted_at is null
                        where p.tenant_id = ?
                          and p.deleted_at is null
                        order by c.sort_order nulls last,
                                 coalesce(lp.sort_order, p.sort_order) asc,
                                 p.name asc
                        """,
                (rs, rowNum) -> catalogProductRow(rs),
                locationId,
                cafeteriaId);

        List<Map<String, Object>> addonGroups = jdbcTemplate.query("""
                        select *
                        from menu.lkp_addon_groups
                        where tenant_id = ?
                          and deleted_at is null
                        order by sort_order asc, name asc
                        """,
                (rs, rowNum) -> addonGroupRow(rs),
                cafeteriaId);

        List<Map<String, Object>> addons = jdbcTemplate.query("""
                        select a.*, g.name as addon_group_name,
                               la.location_addon_id,
                               la.is_available as location_available,
                               la.price_override
                        from menu.lkp_addons a
                        join menu.lkp_addon_groups g on g.addon_group_id = a.addon_group_id
                        left join menu.location_addons la
                          on la.tenant_id = a.tenant_id
                         and la.addon_id = a.addon_id
                         and la.location_id = ?
                         and la.deleted_at is null
                        where a.tenant_id = ?
                          and a.deleted_at is null
                          and g.deleted_at is null
                        order by g.sort_order asc, a.sort_order asc, a.name asc
                        """,
                (rs, rowNum) -> catalogAddonRow(rs),
                locationId,
                cafeteriaId);

        List<Map<String, Object>> productAddonGroups = jdbcTemplate.query("""
                        select pag.*, g.name as addon_group_name
                        from menu.product_addon_groups pag
                        join menu.lkp_addon_groups g on g.addon_group_id = pag.addon_group_id
                        join menu.products p on p.product_id = pag.product_id
                        where pag.tenant_id = ?
                          and pag.deleted_at is null
                          and pag.is_active = true
                          and g.deleted_at is null
                          and p.deleted_at is null
                        order by pag.product_id, pag.sort_order, g.name
                        """,
                (rs, rowNum) -> productAddonGroupRow(rs),
                cafeteriaId);

        long availableProducts = products.stream()
                .filter(product -> Boolean.TRUE.equals(product.get("effectiveAvailable")))
                .count();
        long featuredProducts = products.stream()
                .filter(product -> Boolean.TRUE.equals(product.get("effectiveFeatured")))
                .count();
        long productsWithoutImage = products.stream()
                .filter(product -> product.get("imageUrl") == null)
                .count();

        return new MenuCatalogResponse(
                cafeteriaId,
                locationId,
                row(
                        "categoryCount", categories.size(),
                        "productCount", products.size(),
                        "availableProductCount", availableProducts,
                        "featuredProductCount", featuredProducts,
                        "productsWithoutImageCount", productsWithoutImage,
                        "addonGroupCount", addonGroups.size(),
                        "addonCount", addons.size()
                ),
                categories,
                products,
                addonGroups,
                addons,
                productAddonGroups
        );
    }

    public Map<String, Object> getMenuSettings(Long cafeteriaId) {
        requireTenantAccess(cafeteriaId, false);
        ensureDefaultMenuSettings(cafeteriaId);
        return findMenuSettings(cafeteriaId);
    }

    @Transactional
    public Map<String, Object> updateMenuSettings(Long cafeteriaId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureDefaultMenuSettings(cafeteriaId);

        String defaultLanguageCode = normalizeLanguageCode(stringOrNull(request.get("defaultLanguageCode")));
        String baseCurrencyCode = normalizeCurrencyCode(stringOrNull(request.get("baseCurrencyCode")));
        Boolean allowExchangeConversion = nullableBoolean(request.get("allowExchangeConversion"));

        if (defaultLanguageCode != null) {
            upsertLanguage(cafeteriaId, defaultLanguageCode, row(
                    "displayName", request.getOrDefault("defaultLanguageName", defaultLanguageCode),
                    "default", true,
                    "active", true
            ));
        }
        if (baseCurrencyCode != null) {
            upsertCurrency(cafeteriaId, baseCurrencyCode, row(
                    "displayName", request.getOrDefault("baseCurrencyName", baseCurrencyCode),
                    "symbol", request.get("baseCurrencySymbol"),
                    "base", true,
                    "active", true
            ));
        }

        jdbcTemplate.update("""
                        insert into menu.tenant_menu_settings (
                            tenant_id, default_language_code, base_currency_code, allow_exchange_conversion
                        )
                        values (?, coalesce(?, 'es'), coalesce(?, 'MXN'), coalesce(?, true))
                        on conflict (tenant_id) do update set
                            default_language_code = coalesce(excluded.default_language_code, menu.tenant_menu_settings.default_language_code),
                            base_currency_code = coalesce(excluded.base_currency_code, menu.tenant_menu_settings.base_currency_code),
                            allow_exchange_conversion = coalesce(excluded.allow_exchange_conversion, menu.tenant_menu_settings.allow_exchange_conversion),
                            updated_at = now()
                        """,
                cafeteriaId,
                defaultLanguageCode,
                baseCurrencyCode,
                allowExchangeConversion);
        return findMenuSettings(cafeteriaId);
    }

    public List<Map<String, Object>> listLanguages(Long cafeteriaId) {
        requireTenantAccess(cafeteriaId, false);
        ensureDefaultMenuSettings(cafeteriaId);
        return jdbcTemplate.query("""
                        select *
                        from menu.tenant_supported_languages
                        where tenant_id = ?
                          and deleted_at is null
                        order by is_default desc, sort_order asc, language_code asc
                        """,
                (rs, rowNum) -> languageRow(rs),
                cafeteriaId);
    }

    @Transactional
    public Map<String, Object> upsertLanguageConfig(Long cafeteriaId, String languageCode, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureDefaultMenuSettings(cafeteriaId);
        String normalizedLanguageCode = normalizeLanguageCode(languageCode);
        upsertLanguage(cafeteriaId, normalizedLanguageCode, request);
        if (booleanValue(request.getOrDefault("default", false))) {
            jdbcTemplate.update("""
                            update menu.tenant_menu_settings
                            set default_language_code = ?,
                                updated_at = now()
                            where tenant_id = ?
                            """,
                    normalizedLanguageCode,
                    cafeteriaId);
        }
        return findLanguage(cafeteriaId, normalizedLanguageCode);
    }

    @Transactional
    public void softDeleteLanguage(Long cafeteriaId, String languageCode) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        String normalizedLanguageCode = normalizeLanguageCode(languageCode);
        if (normalizedLanguageCode == null) {
            throw new BadRequestException("languageCode is required");
        }
        Map<String, Object> language = findLanguage(cafeteriaId, normalizedLanguageCode);
        if (Boolean.TRUE.equals(language.get("default"))) {
            throw new BadRequestException("Default language cannot be deleted");
        }
        int updated = jdbcTemplate.update("""
                        update menu.tenant_supported_languages
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and language_code = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                normalizedLanguageCode);
        if (updated == 0) {
            throw new ResourceNotFoundException("Language was not found for this cafeteria");
        }
    }

    public List<Map<String, Object>> listCurrencies(Long cafeteriaId) {
        requireTenantAccess(cafeteriaId, false);
        ensureDefaultMenuSettings(cafeteriaId);
        return jdbcTemplate.query("""
                        select *
                        from menu.tenant_supported_currencies
                        where tenant_id = ?
                          and deleted_at is null
                        order by is_base desc, sort_order asc, currency_code asc
                        """,
                (rs, rowNum) -> currencyRow(rs),
                cafeteriaId);
    }

    @Transactional
    public Map<String, Object> upsertCurrency(Long cafeteriaId, String currencyCode, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureDefaultMenuSettings(cafeteriaId);
        String normalizedCurrencyCode = normalizeCurrencyCode(currencyCode);
        if (normalizedCurrencyCode == null) {
            throw new BadRequestException("currencyCode is required");
        }
        boolean base = booleanValue(request.getOrDefault("base", false));
        if (base) {
            jdbcTemplate.update("""
                            update menu.tenant_supported_currencies
                            set is_base = false,
                                updated_at = now()
                            where tenant_id = ?
                              and deleted_at is null
                            """,
                    cafeteriaId);
        }
        jdbcTemplate.update("""
                        insert into menu.tenant_supported_currencies (
                            tenant_id, currency_code, display_name, symbol, is_base, is_active, sort_order,
                            deleted_at, deleted_by_user_id
                        )
                        values (?, ?, ?, ?, ?, ?, ?, null, null)
                        on conflict (tenant_id, currency_code) do update set
                            display_name = excluded.display_name,
                            symbol = excluded.symbol,
                            is_base = excluded.is_base,
                            is_active = excluded.is_active,
                            sort_order = excluded.sort_order,
                            deleted_at = null,
                            deleted_by_user_id = null,
                            updated_at = now()
                        """,
                cafeteriaId,
                normalizedCurrencyCode,
                stringOrNull(request.get("displayName")) == null ? normalizedCurrencyCode : stringOrNull(request.get("displayName")),
                stringOrNull(request.get("symbol")),
                base,
                booleanValue(request.getOrDefault("active", true)),
                intValue(request.getOrDefault("sortOrder", 0)));
        if (base) {
            jdbcTemplate.update("""
                            update menu.tenant_menu_settings
                            set base_currency_code = ?,
                                updated_at = now()
                            where tenant_id = ?
                            """,
                    normalizedCurrencyCode,
                    cafeteriaId);
        }
        return findCurrency(cafeteriaId, normalizedCurrencyCode);
    }

    @Transactional
    public void softDeleteCurrency(Long cafeteriaId, String currencyCode) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        String normalizedCurrencyCode = normalizeCurrencyCode(currencyCode);
        if (normalizedCurrencyCode == null) {
            throw new BadRequestException("currencyCode is required");
        }
        Map<String, Object> currency = findCurrency(cafeteriaId, normalizedCurrencyCode);
        if (Boolean.TRUE.equals(currency.get("base"))) {
            throw new BadRequestException("Base currency cannot be deleted");
        }
        int updated = jdbcTemplate.update("""
                        update menu.tenant_supported_currencies
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and currency_code = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                normalizedCurrencyCode);
        if (updated == 0) {
            throw new ResourceNotFoundException("Currency was not found for this cafeteria");
        }
    }

    public List<Map<String, Object>> listExchangeRates(Long cafeteriaId) {
        requireTenantAccess(cafeteriaId, false);
        ensureDefaultMenuSettings(cafeteriaId);
        return jdbcTemplate.query("""
                        select *
                        from menu.tenant_exchange_rates
                        where tenant_id = ?
                          and deleted_at is null
                        order by is_active desc, from_currency_code asc, to_currency_code asc, effective_from desc
                        """,
                (rs, rowNum) -> exchangeRateRow(rs),
                cafeteriaId);
    }

    @Transactional
    public Map<String, Object> createExchangeRate(Long cafeteriaId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureDefaultMenuSettings(cafeteriaId);
        String fromCurrencyCode = normalizeCurrencyCode(requiredString(request, "fromCurrencyCode"));
        String toCurrencyCode = normalizeCurrencyCode(requiredString(request, "toCurrencyCode"));
        validateCurrencyPair(cafeteriaId, fromCurrencyCode, toCurrencyCode);
        boolean active = booleanValue(request.getOrDefault("active", true));
        OffsetDateTime effectiveTo = nullableOffsetDateTime(request.get("effectiveTo"));
        if (active && effectiveTo == null) {
            deactivateActiveExchangeRatePair(cafeteriaId, fromCurrencyCode, toCurrencyCode, null);
        }
        Long exchangeRateId = jdbcTemplate.queryForObject("""
                        insert into menu.tenant_exchange_rates (
                            tenant_id, from_currency_code, to_currency_code, rate, effective_from,
                            effective_to, is_active, notes
                        )
                        values (?, ?, ?, ?, coalesce(cast(? as timestamp with time zone), now()), cast(? as timestamp with time zone), ?, ?)
                        returning exchange_rate_id
                        """,
                Long.class,
                cafeteriaId,
                fromCurrencyCode,
                toCurrencyCode,
                decimalValue(request.get("rate")),
                nullableOffsetDateTime(request.get("effectiveFrom")),
                effectiveTo,
                active,
                stringOrNull(request.get("notes")));
        return findExchangeRate(cafeteriaId, exchangeRateId);
    }

    @Transactional
    public Map<String, Object> updateExchangeRate(Long cafeteriaId, Long exchangeRateId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        Map<String, Object> current = findExchangeRate(cafeteriaId, exchangeRateId);
        String fromCurrencyCode = normalizeCurrencyCode(stringOrNull(request.get("fromCurrencyCode")));
        String toCurrencyCode = normalizeCurrencyCode(stringOrNull(request.get("toCurrencyCode")));
        if (fromCurrencyCode == null) {
            fromCurrencyCode = String.valueOf(current.get("fromCurrencyCode"));
        }
        if (toCurrencyCode == null) {
            toCurrencyCode = String.valueOf(current.get("toCurrencyCode"));
        }
        validateCurrencyPair(cafeteriaId, fromCurrencyCode, toCurrencyCode);
        Boolean active = nullableBoolean(request.get("active"));
        OffsetDateTime effectiveTo = request.containsKey("effectiveTo")
                ? nullableOffsetDateTime(request.get("effectiveTo"))
                : (OffsetDateTime) current.get("effectiveTo");
        boolean willBeActive = active == null ? Boolean.TRUE.equals(current.get("active")) : active;
        if (willBeActive && effectiveTo == null) {
            deactivateActiveExchangeRatePair(cafeteriaId, fromCurrencyCode, toCurrencyCode, exchangeRateId);
        }
        jdbcTemplate.update("""
                        update menu.tenant_exchange_rates
                        set from_currency_code = ?,
                            to_currency_code = ?,
                            rate = coalesce(?, rate),
                            effective_from = coalesce(cast(? as timestamp with time zone), effective_from),
                            effective_to = cast(? as timestamp with time zone),
                            is_active = coalesce(?, is_active),
                            notes = coalesce(?, notes),
                            updated_at = now()
                        where tenant_id = ?
                          and exchange_rate_id = ?
                          and deleted_at is null
                        """,
                fromCurrencyCode,
                toCurrencyCode,
                nullableBigDecimal(request.get("rate")),
                nullableOffsetDateTime(request.get("effectiveFrom")),
                effectiveTo,
                active,
                stringOrNull(request.get("notes")),
                cafeteriaId,
                exchangeRateId);
        return findExchangeRate(cafeteriaId, exchangeRateId);
    }

    @Transactional
    public void softDeleteExchangeRate(Long cafeteriaId, Long exchangeRateId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        int updated = jdbcTemplate.update("""
                        update menu.tenant_exchange_rates
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and exchange_rate_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                exchangeRateId);
        if (updated == 0) {
            throw new ResourceNotFoundException("Exchange rate was not found for this cafeteria");
        }
    }

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
        MenuAdminRules.validateCategory(request, true);
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
        MenuAdminRules.validateCategory(request, false);
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

    public List<Map<String, Object>> listCategoryTranslations(Long cafeteriaId, Long categoryId) {
        requireTenantAccess(cafeteriaId, false);
        ensureCategory(cafeteriaId, categoryId);
        return listTranslations(cafeteriaId, "menu.category_translations", "category_translation_id", "category_id", categoryId);
    }

    @Transactional
    public Map<String, Object> upsertCategoryTranslation(Long cafeteriaId, Long categoryId, String languageCode, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureCategory(cafeteriaId, categoryId);
        return upsertTranslation(cafeteriaId, "menu.category_translations", "category_translation_id", "category_id", categoryId, languageCode, request);
    }

    @Transactional
    public void softDeleteCategoryTranslation(Long cafeteriaId, Long categoryId, String languageCode) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureCategory(cafeteriaId, categoryId);
        softDeleteTranslation(cafeteriaId, "menu.category_translations", "category_id", categoryId, languageCode, userId);
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
        product.put("images", listProductImages(cafeteriaId, productId));
        return product;
    }

    public PresignedMediaUpload createProductImageUpload(Long cafeteriaId, Long productId, MediaUploadRequest request) {
        return createProductImageUpload(cafeteriaId, productId, request, true);
    }

    public PresignedMediaUpload createProductGalleryImageUpload(Long cafeteriaId, Long productId, MediaUploadRequest request) {
        return createProductImageUpload(cafeteriaId, productId, request, false);
    }

    private PresignedMediaUpload createProductImageUpload(Long cafeteriaId, Long productId, MediaUploadRequest request, boolean defaultPrimary) {
        Membership membership = requireTenantAccess(cafeteriaId, true);
        ensureProduct(cafeteriaId, productId);
        String purpose = normalizeMediaPurpose(request == null ? null : request.purpose());
        if (!PURPOSE_MENU_PRODUCT_IMAGE.equals(purpose)) {
            throw new BadRequestException("Unsupported menu media purpose");
        }
        Map<String, Object> metadata = metadataWithDefaultPrimary(request.metadata(), defaultPrimary);
        return mediaAssetService.createPresignedUpload(new MediaUploadPolicy(
                "kaffe-msa-menu-admin",
                cafeteriaId,
                null,
                PURPOSE_MENU_PRODUCT_IMAGE,
                "menu_product",
                productId,
                membership.userId(),
                "tenants/%d/menu/products/%d".formatted(cafeteriaId, productId),
                request.fileName(),
                request.contentType(),
                request.sizeBytes() == null ? 0 : request.sizeBytes(),
                request.checksumSha256(),
                metadata
        ));
    }

    @Transactional
    public Map<String, Object> completeProductImageUpload(Long cafeteriaId, Long productId, UUID assetId, MediaCompleteRequest request) {
        Membership membership = requireTenantAccess(cafeteriaId, true);
        ensureProduct(cafeteriaId, productId);
        MediaAsset pending = mediaAssetService.findAsset(assetId, cafeteriaId);
        ensureProductImageAsset(productId, pending);
        MediaAsset asset = mediaAssetService.completeUpload(assetId, cafeteriaId, membership.userId(), request);
        ensureProductImageAsset(productId, asset);
        return attachProductImage(cafeteriaId, productId, asset);
    }

    public List<Map<String, Object>> listProductImages(Long cafeteriaId, Long productId) {
        requireTenantAccess(cafeteriaId, false);
        ensureProduct(cafeteriaId, productId);
        return productImages(cafeteriaId, productId);
    }

    @Transactional
    public Map<String, Object> updateProductImage(Long cafeteriaId, Long productId, Long productImageId, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureProduct(cafeteriaId, productId);
        ensureProductImage(cafeteriaId, productId, productImageId);
        jdbcTemplate.update("""
                        update menu.product_images
                        set alt_text = coalesce(?, alt_text),
                            caption = coalesce(?, caption),
                            sort_order = coalesce(?, sort_order),
                            is_visible = coalesce(?, is_visible),
                            updated_at = now()
                        where tenant_id = ?
                          and product_id = ?
                          and product_image_id = ?
                          and deleted_at is null
                        """,
                stringOrNull(request.get("altText")),
                stringOrNull(request.get("caption")),
                nullableInt(request.get("sortOrder")),
                nullableBoolean(request.get("visible")),
                cafeteriaId,
                productId,
                productImageId
        );
        Boolean primary = nullableBoolean(request.get("primary"));
        if (Boolean.TRUE.equals(primary)) {
            setPrimaryProductImage(cafeteriaId, productId, productImageId);
        } else if (Boolean.FALSE.equals(primary) && isPrimaryProductImage(cafeteriaId, productId, productImageId)) {
            jdbcTemplate.update("""
                            update menu.product_images
                            set is_primary = false,
                                updated_at = now()
                            where tenant_id = ?
                              and product_id = ?
                              and product_image_id = ?
                              and deleted_at is null
                            """,
                    cafeteriaId,
                    productId,
                    productImageId);
            syncPrimaryProductImage(cafeteriaId, productId);
        } else {
            syncPrimaryProductImage(cafeteriaId, productId);
        }
        return findProductImage(cafeteriaId, productId, productImageId);
    }

    @Transactional
    public void softDeleteProductImage(Long cafeteriaId, Long productId, Long productImageId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureProduct(cafeteriaId, productId);
        ensureProductImage(cafeteriaId, productId, productImageId);
        jdbcTemplate.update("""
                        update menu.product_images
                        set deleted_at = now(),
                            deleted_by_user_id = ?,
                            is_visible = false,
                            is_primary = false,
                            updated_at = now()
                        where tenant_id = ?
                          and product_id = ?
                          and product_image_id = ?
                          and deleted_at is null
                        """,
                userId,
                cafeteriaId,
                productId,
                productImageId);
        syncPrimaryProductImage(cafeteriaId, productId);
    }

    @Transactional
    public Map<String, Object> createProduct(Long cafeteriaId, Map<String, Object> request) {
        MenuAdminRules.validateProduct(request, true);
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
        MenuAdminRules.validateProduct(request, false);
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

    public List<Map<String, Object>> listProductTranslations(Long cafeteriaId, Long productId) {
        requireTenantAccess(cafeteriaId, false);
        ensureProduct(cafeteriaId, productId);
        return listTranslations(cafeteriaId, "menu.product_translations", "product_translation_id", "product_id", productId);
    }

    @Transactional
    public Map<String, Object> upsertProductTranslation(Long cafeteriaId, Long productId, String languageCode, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureProduct(cafeteriaId, productId);
        return upsertTranslation(cafeteriaId, "menu.product_translations", "product_translation_id", "product_id", productId, languageCode, request);
    }

    @Transactional
    public void softDeleteProductTranslation(Long cafeteriaId, Long productId, String languageCode) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureProduct(cafeteriaId, productId);
        softDeleteTranslation(cafeteriaId, "menu.product_translations", "product_id", productId, languageCode, userId);
    }

    public List<Map<String, Object>> listProductPrices(Long cafeteriaId, Long productId) {
        requireTenantAccess(cafeteriaId, false);
        ensureProduct(cafeteriaId, productId);
        return listPrices(cafeteriaId, "menu.product_prices", "product_price_id", "product_id", productId, "productPriceId");
    }

    @Transactional
    public Map<String, Object> upsertProductPrice(Long cafeteriaId, Long productId, String currencyCode, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureProduct(cafeteriaId, productId);
        return upsertPrice(cafeteriaId, "menu.product_prices", "product_price_id", "product_id", productId, "productPriceId", currencyCode, request);
    }

    @Transactional
    public void softDeleteProductPrice(Long cafeteriaId, Long productId, Long productPriceId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureProduct(cafeteriaId, productId);
        softDeletePrice(cafeteriaId, "menu.product_prices", "product_price_id", "product_id", productId, productPriceId, userId);
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
        MenuAdminRules.validateAddonGroup(request, true);
        requireTenantAccess(cafeteriaId, true);
        Boolean required = booleanValue(request.getOrDefault("required", false));
        Integer minSelection = intValue(request.getOrDefault("minSelection", 0));
        Integer maxSelection = nullableInt(request.get("maxSelection"));
        validateSelection(required, minSelection, maxSelection);
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
                minSelection,
                maxSelection,
                required,
                intValue(request.getOrDefault("sortOrder", 0)),
                booleanValue(request.getOrDefault("active", true)));
        return getAddonGroup(cafeteriaId, addonGroupId);
    }

    @Transactional
    public Map<String, Object> updateAddonGroup(Long cafeteriaId, Long addonGroupId, Map<String, Object> request) {
        MenuAdminRules.validateAddonGroup(request, false);
        requireTenantAccess(cafeteriaId, true);
        Map<String, Object> current = findAddonGroup(cafeteriaId, addonGroupId);
        Boolean required = nullableBoolean(request.get("required"));
        Integer minSelection = nullableInt(request.get("minSelection"));
        Integer maxSelection = nullableInt(request.get("maxSelection"));
        validateSelection(
                required == null ? (Boolean) current.get("required") : required,
                minSelection == null ? (Integer) current.get("minSelection") : minSelection,
                maxSelection == null ? (Integer) current.get("maxSelection") : maxSelection
        );
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

    public List<Map<String, Object>> listAddonGroupTranslations(Long cafeteriaId, Long addonGroupId) {
        requireTenantAccess(cafeteriaId, false);
        ensureAddonGroup(cafeteriaId, addonGroupId);
        return listTranslations(cafeteriaId, "menu.addon_group_translations", "addon_group_translation_id", "addon_group_id", addonGroupId);
    }

    @Transactional
    public Map<String, Object> upsertAddonGroupTranslation(Long cafeteriaId, Long addonGroupId, String languageCode, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureAddonGroup(cafeteriaId, addonGroupId);
        return upsertTranslation(cafeteriaId, "menu.addon_group_translations", "addon_group_translation_id", "addon_group_id", addonGroupId, languageCode, request);
    }

    @Transactional
    public void softDeleteAddonGroupTranslation(Long cafeteriaId, Long addonGroupId, String languageCode) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureAddonGroup(cafeteriaId, addonGroupId);
        softDeleteTranslation(cafeteriaId, "menu.addon_group_translations", "addon_group_id", addonGroupId, languageCode, userId);
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
        MenuAdminRules.validateAddon(request, true);
        requireTenantAccess(cafeteriaId, true);
        ensureAddonGroup(cafeteriaId, addonGroupId);
        boolean isDefault = booleanValue(request.getOrDefault("isDefault", request.getOrDefault("default", false)));
        if (isDefault) {
            clearDefaultAddons(cafeteriaId, addonGroupId, null);
        }
        Long addonId = jdbcTemplate.queryForObject("""
                        insert into menu.lkp_addons (
                            tenant_id, addon_group_id, name, description, price, sort_order, is_active, is_default
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        returning addon_id
                        """,
                Long.class,
                cafeteriaId,
                addonGroupId,
                requiredString(request, "name"),
                stringOrNull(request.get("description")),
                intValue(request.getOrDefault("price", 0)),
                intValue(request.getOrDefault("sortOrder", 0)),
                booleanValue(request.getOrDefault("active", true)),
                isDefault);
        return findAddon(cafeteriaId, addonId);
    }

    @Transactional
    public Map<String, Object> updateAddon(Long cafeteriaId, Long addonGroupId, Long addonId, Map<String, Object> request) {
        MenuAdminRules.validateAddon(request, false);
        requireTenantAccess(cafeteriaId, true);
        ensureAddon(cafeteriaId, addonGroupId, addonId);
        Boolean isDefault = nullableBoolean(request.containsKey("isDefault") ? request.get("isDefault") : request.get("default"));
        if (Boolean.TRUE.equals(isDefault)) {
            clearDefaultAddons(cafeteriaId, addonGroupId, addonId);
        }
        jdbcTemplate.update("""
                        update menu.lkp_addons
                        set name = coalesce(?, name),
                            description = coalesce(?, description),
                            price = coalesce(?, price),
                            sort_order = coalesce(?, sort_order),
                            is_active = coalesce(?, is_active),
                            is_default = coalesce(?, is_default),
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
                isDefault,
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

    public List<Map<String, Object>> listAddonTranslations(Long cafeteriaId, Long addonId) {
        requireTenantAccess(cafeteriaId, false);
        ensureAddon(cafeteriaId, addonId);
        return listTranslations(cafeteriaId, "menu.addon_translations", "addon_translation_id", "addon_id", addonId);
    }

    @Transactional
    public Map<String, Object> upsertAddonTranslation(Long cafeteriaId, Long addonId, String languageCode, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureAddon(cafeteriaId, addonId);
        return upsertTranslation(cafeteriaId, "menu.addon_translations", "addon_translation_id", "addon_id", addonId, languageCode, request);
    }

    @Transactional
    public void softDeleteAddonTranslation(Long cafeteriaId, Long addonId, String languageCode) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureAddon(cafeteriaId, addonId);
        softDeleteTranslation(cafeteriaId, "menu.addon_translations", "addon_id", addonId, languageCode, userId);
    }

    public List<Map<String, Object>> listAddonPrices(Long cafeteriaId, Long addonId) {
        requireTenantAccess(cafeteriaId, false);
        ensureAddon(cafeteriaId, addonId);
        return listPrices(cafeteriaId, "menu.addon_prices", "addon_price_id", "addon_id", addonId, "addonPriceId");
    }

    @Transactional
    public Map<String, Object> upsertAddonPrice(Long cafeteriaId, Long addonId, String currencyCode, Map<String, Object> request) {
        requireTenantAccess(cafeteriaId, true);
        ensureAddon(cafeteriaId, addonId);
        return upsertPrice(cafeteriaId, "menu.addon_prices", "addon_price_id", "addon_id", addonId, "addonPriceId", currencyCode, request);
    }

    @Transactional
    public void softDeleteAddonPrice(Long cafeteriaId, Long addonId, Long addonPriceId) {
        Long userId = requireTenantAccess(cafeteriaId, true).userId();
        ensureAddon(cafeteriaId, addonId);
        softDeletePrice(cafeteriaId, "menu.addon_prices", "addon_price_id", "addon_id", addonId, addonPriceId, userId);
    }

    @Transactional
    public Map<String, Object> configureLocationProduct(Long cafeteriaId, Long locationId, Long productId, Map<String, Object> request) {
        MenuAdminRules.validateLocationConfiguration(request);
        requireLocationAccess(cafeteriaId, locationId, true);
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
        MenuAdminRules.validateLocationConfiguration(request);
        requireLocationAccess(cafeteriaId, locationId, true);
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
        validateSelection(required, minSelection, maxSelection);
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
                            select ura.user_id, ura.tenant_id, tr.code as role_code
                            from auth.tenant_user_role_assignments ura
                            join auth.tenant_roles tr
                              on tr.tenant_role_id = ura.tenant_role_id
                             and tr.tenant_id = ura.tenant_id
                             and tr.status_id = 1
                            join auth.tenant_role_policies trp
                              on trp.tenant_role_id = tr.tenant_role_id
                             and trp.enabled = true
                            join catalog.lkp_policies p
                              on p.policy_id = trp.policy_id
                             and p.status_id = 1
                            where ura.user_id = ?
                              and ura.tenant_id = ?
                              and ura.status_id = 1
                              and (
                                  (? = true and ura.location_id is null and p.code = ?)
                                  or
                                  (? = false and p.code in (?, ?))
                              )
                            order by ura.is_default desc, ura.created_at desc
                            limit 1
                            """),
                    (rs, rowNum) -> new Membership(
                            rs.getLong("user_id"),
                            rs.getLong("tenant_id"),
                            rs.getString("role_code")
                    ),
                    userId,
                    cafeteriaId,
                    managerRequired,
                    POLICY_MENU_WRITE,
                    managerRequired,
                    POLICY_MENU_READ,
                    POLICY_MENU_WRITE);
            return membership;
        } catch (EmptyResultDataAccessException ex) {
            throwMenuAccess(cafeteriaId, null, managerRequired);
            throw ex;
        }
    }

    private Membership requireLocationAccess(Long cafeteriaId, Long locationId, boolean writeRequired) {
        Long userId = currentUserProvider.requireUserId();
        try {
            return jdbcTemplate.queryForObject(authSql("""
                            select ura.user_id, ura.tenant_id, tr.code as role_code
                            from auth.tenant_user_role_assignments ura
                            join auth.tenant_roles tr
                              on tr.tenant_role_id = ura.tenant_role_id
                             and tr.tenant_id = ura.tenant_id
                             and tr.status_id = 1
                            join auth.tenant_role_policies trp
                              on trp.tenant_role_id = tr.tenant_role_id
                             and trp.enabled = true
                            join catalog.lkp_policies p
                              on p.policy_id = trp.policy_id
                             and p.status_id = 1
                            join core.locations l
                              on l.tenant_id = ura.tenant_id
                             and l.location_id = ?
                             and l.status_id <> 4
                            where ura.user_id = ?
                              and ura.tenant_id = ?
                              and ura.status_id = 1
                              and (ura.location_id is null or ura.location_id = l.location_id)
                              and (
                                  (? = true and p.code = ?)
                                  or
                                  (? = false and p.code in (?, ?))
                              )
                            order by
                                case when ura.location_id = l.location_id then 0 else 1 end,
                                ura.is_default desc,
                                ura.created_at desc
                            limit 1
                            """),
                    (rs, rowNum) -> new Membership(
                            rs.getLong("user_id"),
                            rs.getLong("tenant_id"),
                            rs.getString("role_code")
                    ),
                    locationId,
                    userId,
                    cafeteriaId,
                    writeRequired,
                    POLICY_MENU_WRITE,
                    writeRequired,
                    POLICY_MENU_READ,
                    POLICY_MENU_WRITE);
        } catch (EmptyResultDataAccessException ex) {
            throwMenuAccess(cafeteriaId, locationId, writeRequired);
            throw ex;
        }
    }

    private void throwMenuAccess(Long cafeteriaId, Long locationId, boolean writeRequired) {
        if (locationId != null) {
            ensureLocationExistsForAccess(cafeteriaId, locationId);
        }

        Long userId = currentUserProvider.requireUserId();
        String locationScope = locationId == null
                ? ""
                : "and (location_id is null or location_id = ?)";
        Object[] args = locationId == null
                ? new Object[]{userId, cafeteriaId}
                : new Object[]{userId, cafeteriaId, locationId};
        Boolean assigned = jdbcTemplate.queryForObject(authSql("""
                        select exists(
                            select 1
                            from auth.tenant_user_role_assignments
                            where user_id = ?
                              and tenant_id = ?
                              and status_id = 1
                              %s
                        )
                        """.formatted(locationScope)),
                Boolean.class,
                args);
        if (Boolean.TRUE.equals(assigned)) {
            throw new ForbiddenException(writeRequired
                    ? "User cannot manage menu in this scope"
                    : "User cannot read menu in this scope");
        }
        throw new ResourceNotFoundException(locationId == null
                ? "Cafeteria was not found for this user"
                : "Location was not found for this user");
    }

    private void ensureLocationExistsForAccess(Long cafeteriaId, Long locationId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1
                            from core.locations
                            where tenant_id = ?
                              and location_id = ?
                              and status_id <> 4
                        )
                        """,
                Boolean.class,
                cafeteriaId,
                locationId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResourceNotFoundException("Location was not found for this cafeteria");
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

    private void ensureExists(String sql, String message, Object... args) {
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, args);
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

    private List<Map<String, Object>> productImages(Long cafeteriaId, Long productId) {
        return jdbcTemplate.query("""
                        select *
                        from menu.product_images
                        where tenant_id = ?
                          and product_id = ?
                          and deleted_at is null
                        order by is_primary desc, sort_order asc, product_image_id asc
                        """,
                (rs, rowNum) -> productImageRow(rs),
                cafeteriaId,
                productId);
    }

    private Map<String, Object> findProductImage(Long cafeteriaId, Long productId, Long productImageId) {
        return queryOne("""
                        select *
                        from menu.product_images
                        where tenant_id = ?
                          and product_id = ?
                          and product_image_id = ?
                          and deleted_at is null
                        """,
                rs -> productImageRow(rs),
                cafeteriaId,
                productId,
                productImageId);
    }

    private void ensureProductImage(Long cafeteriaId, Long productId, Long productImageId) {
        ensureExists("""
                        select exists(
                            select 1
                            from menu.product_images
                            where tenant_id = ?
                              and product_id = ?
                              and product_image_id = ?
                              and deleted_at is null
                        )
                        """,
                "Product image was not found for this product",
                cafeteriaId,
                productId,
                productImageId);
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

    private void ensureDefaultMenuSettings(Long cafeteriaId) {
        jdbcTemplate.update("""
                        insert into menu.tenant_menu_settings (tenant_id, default_language_code, base_currency_code)
                        values (?, 'es', 'MXN')
                        on conflict (tenant_id) do nothing
                        """,
                cafeteriaId);
        jdbcTemplate.update("""
                        insert into menu.tenant_supported_languages (
                            tenant_id, language_code, display_name, is_default, is_active, sort_order
                        )
                        values (?, 'es', 'Espanol', true, true, 1)
                        on conflict (tenant_id, language_code) do nothing
                        """,
                cafeteriaId);
        jdbcTemplate.update("""
                        insert into menu.tenant_supported_currencies (
                            tenant_id, currency_code, display_name, symbol, is_base, is_active, sort_order
                        )
                        values (?, 'MXN', 'Peso mexicano', '$', true, true, 1)
                        on conflict (tenant_id, currency_code) do nothing
                        """,
                cafeteriaId);
    }

    private Map<String, Object> findMenuSettings(Long cafeteriaId) {
        return queryOne("""
                        select *
                        from menu.tenant_menu_settings
                        where tenant_id = ?
                          and deleted_at is null
                        """,
                rs -> menuSettingsRow(rs),
                cafeteriaId);
    }

    private Map<String, Object> findLanguage(Long cafeteriaId, String languageCode) {
        return queryOne("""
                        select *
                        from menu.tenant_supported_languages
                        where tenant_id = ?
                          and language_code = ?
                          and deleted_at is null
                        """,
                rs -> languageRow(rs),
                cafeteriaId,
                languageCode);
    }

    private void upsertLanguage(Long cafeteriaId, String languageCode, Map<String, Object> request) {
        if (languageCode == null) {
            throw new BadRequestException("languageCode is required");
        }
        boolean defaultLanguage = booleanValue(request.getOrDefault("default", false));
        if (defaultLanguage) {
            jdbcTemplate.update("""
                            update menu.tenant_supported_languages
                            set is_default = false,
                                updated_at = now()
                            where tenant_id = ?
                              and deleted_at is null
                            """,
                    cafeteriaId);
        }
        jdbcTemplate.update("""
                        insert into menu.tenant_supported_languages (
                            tenant_id, language_code, display_name, is_default, is_active, sort_order,
                            deleted_at, deleted_by_user_id
                        )
                        values (?, ?, ?, ?, ?, ?, null, null)
                        on conflict (tenant_id, language_code) do update set
                            display_name = excluded.display_name,
                            is_default = excluded.is_default,
                            is_active = excluded.is_active,
                            sort_order = excluded.sort_order,
                            deleted_at = null,
                            deleted_by_user_id = null,
                            updated_at = now()
                        """,
                cafeteriaId,
                languageCode,
                stringOrNull(request.get("displayName")) == null ? languageCode : stringOrNull(request.get("displayName")),
                defaultLanguage,
                booleanValue(request.getOrDefault("active", true)),
                intValue(request.getOrDefault("sortOrder", 0)));
    }

    private Map<String, Object> findCurrency(Long cafeteriaId, String currencyCode) {
        return queryOne("""
                        select *
                        from menu.tenant_supported_currencies
                        where tenant_id = ?
                          and currency_code = ?
                          and deleted_at is null
                        """,
                rs -> currencyRow(rs),
                cafeteriaId,
                currencyCode);
    }

    private Map<String, Object> findExchangeRate(Long cafeteriaId, Long exchangeRateId) {
        return queryOne("""
                        select *
                        from menu.tenant_exchange_rates
                        where tenant_id = ?
                          and exchange_rate_id = ?
                          and deleted_at is null
                        """,
                rs -> exchangeRateRow(rs),
                cafeteriaId,
                exchangeRateId);
    }

    private void validateCurrencyPair(Long cafeteriaId, String fromCurrencyCode, String toCurrencyCode) {
        if (fromCurrencyCode == null || toCurrencyCode == null) {
            throw new BadRequestException("fromCurrencyCode and toCurrencyCode are required");
        }
        if (fromCurrencyCode.equals(toCurrencyCode)) {
            throw new BadRequestException("fromCurrencyCode and toCurrencyCode must be different");
        }
        findCurrency(cafeteriaId, fromCurrencyCode);
        findCurrency(cafeteriaId, toCurrencyCode);
    }

    private void deactivateActiveExchangeRatePair(
            Long cafeteriaId,
            String fromCurrencyCode,
            String toCurrencyCode,
            Long exceptExchangeRateId
    ) {
        String exceptCondition = exceptExchangeRateId == null ? "" : "  and exchange_rate_id <> ?\n";
        List<Object> params = new ArrayList<>(List.of(cafeteriaId, fromCurrencyCode, toCurrencyCode));
        if (exceptExchangeRateId != null) {
            params.add(exceptExchangeRateId);
        }
        jdbcTemplate.update("""
                        update menu.tenant_exchange_rates
                        set is_active = false,
                            effective_to = coalesce(effective_to, now()),
                            updated_at = now()
                        where tenant_id = ?
                          and from_currency_code = ?
                          and to_currency_code = ?
                          and is_active = true
                          and deleted_at is null
                          and effective_to is null
                        """ + exceptCondition,
                params.toArray());
    }

    private List<Map<String, Object>> listTranslations(
            Long cafeteriaId,
            String tableName,
            String translationIdColumn,
            String entityIdColumn,
            Long entityId
    ) {
        return jdbcTemplate.query("""
                        select *
                        from %s
                        where tenant_id = ?
                          and %s = ?
                          and deleted_at is null
                        order by language_code asc
                        """.formatted(tableName, entityIdColumn),
                (rs, rowNum) -> translationRow(rs, translationIdColumn, entityIdColumn),
                cafeteriaId,
                entityId);
    }

    private Map<String, Object> upsertTranslation(
            Long cafeteriaId,
            String tableName,
            String translationIdColumn,
            String entityIdColumn,
            Long entityId,
            String languageCode,
            Map<String, Object> request
    ) {
        String normalizedLanguageCode = normalizeLanguageCode(languageCode);
        findLanguage(cafeteriaId, normalizedLanguageCode);
        Long translationId = jdbcTemplate.queryForObject("""
                        insert into %s (
                            tenant_id, %s, language_code, name, description, is_active,
                            deleted_at, deleted_by_user_id
                        )
                        values (?, ?, ?, ?, ?, ?, null, null)
                        on conflict (%s, language_code) where deleted_at is null do update set
                            name = excluded.name,
                            description = excluded.description,
                            is_active = excluded.is_active,
                            deleted_at = null,
                            deleted_by_user_id = null,
                            updated_at = now()
                        returning %s
                        """.formatted(tableName, entityIdColumn, entityIdColumn, translationIdColumn),
                Long.class,
                cafeteriaId,
                entityId,
                normalizedLanguageCode,
                requiredString(request, "name"),
                stringOrNull(request.get("description")),
                booleanValue(request.getOrDefault("active", true)));
        return findTranslation(cafeteriaId, tableName, translationIdColumn, entityIdColumn, entityId, translationId);
    }

    private Map<String, Object> findTranslation(
            Long cafeteriaId,
            String tableName,
            String translationIdColumn,
            String entityIdColumn,
            Long entityId,
            Long translationId
    ) {
        return queryOne("""
                        select *
                        from %s
                        where tenant_id = ?
                          and %s = ?
                          and %s = ?
                          and deleted_at is null
                        """.formatted(tableName, entityIdColumn, translationIdColumn),
                rs -> translationRow(rs, translationIdColumn, entityIdColumn),
                cafeteriaId,
                entityId,
                translationId);
    }

    private void softDeleteTranslation(
            Long cafeteriaId,
            String tableName,
            String entityIdColumn,
            Long entityId,
            String languageCode,
            Long userId
    ) {
        String normalizedLanguageCode = normalizeLanguageCode(languageCode);
        int updated = jdbcTemplate.update("""
                        update %s
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and %s = ?
                          and language_code = ?
                          and deleted_at is null
                        """.formatted(tableName, entityIdColumn),
                userId,
                cafeteriaId,
                entityId,
                normalizedLanguageCode);
        if (updated == 0) {
            throw new ResourceNotFoundException("Translation was not found");
        }
    }

    private List<Map<String, Object>> listPrices(
            Long cafeteriaId,
            String tableName,
            String priceIdColumn,
            String entityIdColumn,
            Long entityId,
            String responseIdName
    ) {
        return jdbcTemplate.query("""
                        select p.*, l.name as location_name
                        from %s p
                        left join core.locations l
                          on l.location_id = p.location_id
                         and l.tenant_id = p.tenant_id
                        where p.tenant_id = ?
                          and p.%s = ?
                          and p.deleted_at is null
                        order by p.is_active desc, p.location_id nulls first, p.currency_code asc, p.effective_from desc
                        """.formatted(tableName, entityIdColumn),
                (rs, rowNum) -> priceRow(rs, priceIdColumn, entityIdColumn, responseIdName),
                cafeteriaId,
                entityId);
    }

    private Map<String, Object> upsertPrice(
            Long cafeteriaId,
            String tableName,
            String priceIdColumn,
            String entityIdColumn,
            Long entityId,
            String responseIdName,
            String currencyCode,
            Map<String, Object> request
    ) {
        String normalizedCurrencyCode = normalizeCurrencyCode(currencyCode);
        findCurrency(cafeteriaId, normalizedCurrencyCode);
        Long locationId = nullableLong(request.get("locationId"));
        if (locationId != null) {
            ensureLocation(cafeteriaId, locationId);
        }
        Integer amount = moneyAmount(request.get("amount"));
        boolean active = booleanValue(request.getOrDefault("active", true));
        OffsetDateTime effectiveTo = nullableOffsetDateTime(request.get("effectiveTo"));
        if (active && effectiveTo == null) {
            deactivateActivePrice(cafeteriaId, tableName, entityIdColumn, entityId, normalizedCurrencyCode, locationId);
        }
        Long priceId = jdbcTemplate.queryForObject("""
                        insert into %s (
                            tenant_id, %s, location_id, currency_code, amount, is_active,
                            effective_from, effective_to, deleted_at, deleted_by_user_id
                        )
                        values (?, ?, ?, ?, ?, ?, coalesce(cast(? as timestamp with time zone), now()), cast(? as timestamp with time zone), null, null)
                        returning %s
                        """.formatted(tableName, entityIdColumn, priceIdColumn),
                Long.class,
                cafeteriaId,
                entityId,
                locationId,
                normalizedCurrencyCode,
                amount,
                active,
                nullableOffsetDateTime(request.get("effectiveFrom")),
                effectiveTo);
        return findPrice(cafeteriaId, tableName, priceIdColumn, entityIdColumn, entityId, priceId, responseIdName);
    }

    private Map<String, Object> findPrice(
            Long cafeteriaId,
            String tableName,
            String priceIdColumn,
            String entityIdColumn,
            Long entityId,
            Long priceId,
            String responseIdName
    ) {
        return queryOne("""
                        select p.*, l.name as location_name
                        from %s p
                        left join core.locations l
                          on l.location_id = p.location_id
                         and l.tenant_id = p.tenant_id
                        where p.tenant_id = ?
                          and p.%s = ?
                          and p.%s = ?
                          and p.deleted_at is null
                        """.formatted(tableName, entityIdColumn, priceIdColumn),
                rs -> priceRow(rs, priceIdColumn, entityIdColumn, responseIdName),
                cafeteriaId,
                entityId,
                priceId);
    }

    private void deactivateActivePrice(
            Long cafeteriaId,
            String tableName,
            String entityIdColumn,
            Long entityId,
            String currencyCode,
            Long locationId
    ) {
        String locationCondition = locationId == null ? "  and location_id is null\n" : "  and location_id = ?\n";
        List<Object> params = new ArrayList<>(List.of(cafeteriaId, entityId, currencyCode));
        if (locationId != null) {
            params.add(locationId);
        }
        jdbcTemplate.update("""
                        update %s
                        set is_active = false,
                            effective_to = coalesce(effective_to, now()),
                            updated_at = now()
                        where tenant_id = ?
                          and %s = ?
                          and currency_code = ?
                          and is_active = true
                          and deleted_at is null
                          and effective_to is null
                        %s
                        """.formatted(tableName, entityIdColumn, locationCondition),
                params.toArray());
    }

    private void softDeletePrice(
            Long cafeteriaId,
            String tableName,
            String priceIdColumn,
            String entityIdColumn,
            Long entityId,
            Long priceId,
            Long userId
    ) {
        int updated = jdbcTemplate.update("""
                        update %s
                        set is_active = false,
                            deleted_at = now(),
                            deleted_by_user_id = ?,
                            updated_at = now()
                        where tenant_id = ?
                          and %s = ?
                          and %s = ?
                          and deleted_at is null
                        """.formatted(tableName, entityIdColumn, priceIdColumn),
                userId,
                cafeteriaId,
                entityId,
                priceId);
        if (updated == 0) {
            throw new ResourceNotFoundException("Price was not found");
        }
    }

    private Map<String, Object> menuSettingsRow(ResultSet rs) throws SQLException {
        return row(
                "cafeteriaId", rs.getLong("tenant_id"),
                "defaultLanguageCode", rs.getString("default_language_code"),
                "baseCurrencyCode", rs.getString("base_currency_code"),
                "allowExchangeConversion", rs.getBoolean("allow_exchange_conversion")
        );
    }

    private Map<String, Object> languageRow(ResultSet rs) throws SQLException {
        return row(
                "cafeteriaId", rs.getLong("tenant_id"),
                "languageCode", rs.getString("language_code"),
                "displayName", rs.getString("display_name"),
                "default", rs.getBoolean("is_default"),
                "active", rs.getBoolean("is_active"),
                "sortOrder", rs.getInt("sort_order"),
                "createdAt", rs.getTimestamp("created_at"),
                "updatedAt", rs.getTimestamp("updated_at")
        );
    }

    private Map<String, Object> currencyRow(ResultSet rs) throws SQLException {
        return row(
                "cafeteriaId", rs.getLong("tenant_id"),
                "currencyCode", rs.getString("currency_code"),
                "displayName", rs.getString("display_name"),
                "symbol", rs.getString("symbol"),
                "base", rs.getBoolean("is_base"),
                "active", rs.getBoolean("is_active"),
                "sortOrder", rs.getInt("sort_order"),
                "createdAt", rs.getTimestamp("created_at"),
                "updatedAt", rs.getTimestamp("updated_at")
        );
    }

    private Map<String, Object> exchangeRateRow(ResultSet rs) throws SQLException {
        return row(
                "exchangeRateId", rs.getLong("exchange_rate_id"),
                "cafeteriaId", rs.getLong("tenant_id"),
                "fromCurrencyCode", rs.getString("from_currency_code"),
                "toCurrencyCode", rs.getString("to_currency_code"),
                "rate", rs.getBigDecimal("rate"),
                "effectiveFrom", rs.getObject("effective_from", OffsetDateTime.class),
                "effectiveTo", rs.getObject("effective_to", OffsetDateTime.class),
                "active", rs.getBoolean("is_active"),
                "notes", rs.getString("notes"),
                "createdAt", rs.getTimestamp("created_at"),
                "updatedAt", rs.getTimestamp("updated_at")
        );
    }

    private Map<String, Object> translationRow(ResultSet rs, String translationIdColumn, String entityIdColumn) throws SQLException {
        return row(
                "translationId", rs.getLong(translationIdColumn),
                "cafeteriaId", rs.getLong("tenant_id"),
                entityIdKey(entityIdColumn), rs.getLong(entityIdColumn),
                "languageCode", rs.getString("language_code"),
                "name", rs.getString("name"),
                "description", rs.getString("description"),
                "active", rs.getBoolean("is_active"),
                "createdAt", rs.getTimestamp("created_at"),
                "updatedAt", rs.getTimestamp("updated_at")
        );
    }

    private Map<String, Object> priceRow(ResultSet rs, String priceIdColumn, String entityIdColumn, String responseIdName) throws SQLException {
        return row(
                responseIdName, rs.getLong(priceIdColumn),
                "priceId", rs.getLong(priceIdColumn),
                "cafeteriaId", rs.getLong("tenant_id"),
                entityIdKey(entityIdColumn), rs.getLong(entityIdColumn),
                "locationId", nullableColumnLong(rs, "location_id"),
                "locationName", nullableColumnString(rs, "location_name"),
                "currencyCode", rs.getString("currency_code"),
                "amount", rs.getInt("amount"),
                "active", rs.getBoolean("is_active"),
                "effectiveFrom", rs.getObject("effective_from", OffsetDateTime.class),
                "effectiveTo", rs.getObject("effective_to", OffsetDateTime.class),
                "createdAt", rs.getTimestamp("created_at"),
                "updatedAt", rs.getTimestamp("updated_at")
        );
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
                "imageAssetId", nullableColumnString(rs, "image_asset_id"),
                "featured", rs.getBoolean("is_featured"),
                "available", rs.getBoolean("is_available"),
                "sortOrder", rs.getInt("sort_order")
        );
    }

    private Map<String, Object> catalogProductRow(ResultSet rs) throws SQLException {
        Map<String, Object> product = productRow(rs);
        Long locationProductId = nullableColumnLong(rs, "location_product_id");
        Integer priceOverride = nullableColumnInt(rs, "price_override");
        boolean globalAvailable = Boolean.TRUE.equals(product.get("available"));
        boolean globalFeatured = Boolean.TRUE.equals(product.get("featured"));
        boolean locationConfigured = locationProductId != null;
        boolean effectiveAvailable = globalAvailable
                && (!locationConfigured || rs.getBoolean("location_available"));
        boolean effectiveFeatured = locationConfigured
                ? rs.getBoolean("location_featured")
                : globalFeatured;
        int effectiveSortOrder = locationConfigured
                ? rs.getInt("location_sort_order")
                : (Integer) product.get("sortOrder");

        product.put("locationProductId", locationProductId);
        product.put("locationConfigured", locationConfigured);
        product.put("priceOverride", priceOverride);
        product.put("effectivePrice", priceOverride == null ? product.get("basePrice") : priceOverride);
        product.put("effectiveAvailable", effectiveAvailable);
        product.put("effectiveFeatured", effectiveFeatured);
        product.put("effectiveSortOrder", effectiveSortOrder);
        return product;
    }

    private Map<String, Object> productImageRow(ResultSet rs) throws SQLException {
        return row(
                "productImageId", rs.getLong("product_image_id"),
                "cafeteriaId", rs.getLong("tenant_id"),
                "productId", rs.getLong("product_id"),
                "assetId", nullableColumnString(rs, "asset_id"),
                "imageUrl", rs.getString("image_url"),
                "altText", rs.getString("alt_text"),
                "caption", rs.getString("caption"),
                "sortOrder", rs.getInt("sort_order"),
                "primary", rs.getBoolean("is_primary"),
                "visible", rs.getBoolean("is_visible"),
                "createdAt", rs.getTimestamp("created_at"),
                "updatedAt", rs.getTimestamp("updated_at")
        );
    }

    private Map<String, Object> attachProductImage(Long cafeteriaId, Long productId, MediaAsset asset) {
        boolean hasImages = hasProductImages(cafeteriaId, productId);
        boolean primary = metadataBoolean(asset, "primary", !hasImages);
        boolean visible = metadataBoolean(asset, "visible", true);
        if (primary) {
            clearPrimaryProductImages(cafeteriaId, productId);
        }

        Long productImageId = jdbcTemplate.queryForObject("""
                        insert into menu.product_images (
                            tenant_id, product_id, asset_id, image_url, alt_text, caption,
                            sort_order, is_primary, is_visible
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        returning product_image_id
                        """,
                Long.class,
                cafeteriaId,
                productId,
                asset.assetId(),
                asset.publicUrl(),
                metadataString(asset, "altText", 160),
                metadataString(asset, "caption", 180),
                metadataInt(asset, "sortOrder", nextProductImageSortOrder(cafeteriaId, productId)),
                primary,
                visible
        );

        if (primary || !hasPrimaryProductImage(cafeteriaId, productId)) {
            applyPrimaryProductImage(cafeteriaId, productId, productImageId);
        } else {
            syncPrimaryProductImage(cafeteriaId, productId);
        }
        return findProductImage(cafeteriaId, productId, productImageId);
    }

    private void ensureProductImageAsset(Long productId, MediaAsset asset) {
        if (!PURPOSE_MENU_PRODUCT_IMAGE.equals(asset.purpose())
                || !"menu_product".equals(asset.entityType())
                || !productId.equals(asset.entityId())) {
            throw new BadRequestException("Media asset does not belong to this product");
        }
    }

    private void setPrimaryProductImage(Long cafeteriaId, Long productId, Long productImageId) {
        Map<String, Object> image = findProductImage(cafeteriaId, productId, productImageId);
        if (!Boolean.TRUE.equals(image.get("visible"))) {
            throw new BadRequestException("Only a visible product image can be primary");
        }
        applyPrimaryProductImage(cafeteriaId, productId, productImageId);
    }

    private void syncPrimaryProductImage(Long cafeteriaId, Long productId) {
        Long primaryImageId = jdbcTemplate.query("""
                        select product_image_id
                        from menu.product_images
                        where tenant_id = ?
                          and product_id = ?
                          and is_visible = true
                          and deleted_at is null
                        order by is_primary desc, sort_order asc, product_image_id asc
                        limit 1
                        """,
                rs -> rs.next() ? rs.getObject("product_image_id", Long.class) : null,
                cafeteriaId,
                productId);
        if (primaryImageId == null) {
            jdbcTemplate.update("""
                            update menu.products
                            set image_url = null,
                                image_asset_id = null,
                                updated_at = now()
                            where tenant_id = ?
                              and product_id = ?
                              and deleted_at is null
                            """,
                    cafeteriaId,
                    productId);
            return;
        }
        applyPrimaryProductImage(cafeteriaId, productId, primaryImageId);
    }

    private void applyPrimaryProductImage(Long cafeteriaId, Long productId, Long productImageId) {
        clearPrimaryProductImages(cafeteriaId, productId);
        jdbcTemplate.update("""
                        update menu.product_images
                        set is_primary = true,
                            is_visible = true,
                            updated_at = now()
                        where tenant_id = ?
                          and product_id = ?
                          and product_image_id = ?
                          and deleted_at is null
                        """,
                cafeteriaId,
                productId,
                productImageId);
        jdbcTemplate.update("""
                        update menu.products p
                        set image_url = pi.image_url,
                            image_asset_id = pi.asset_id,
                            updated_at = now()
                        from menu.product_images pi
                        where p.tenant_id = pi.tenant_id
                          and p.product_id = pi.product_id
                          and pi.tenant_id = ?
                          and pi.product_id = ?
                          and pi.product_image_id = ?
                          and pi.deleted_at is null
                          and p.deleted_at is null
                        """,
                cafeteriaId,
                productId,
                productImageId);
    }

    private void clearPrimaryProductImages(Long cafeteriaId, Long productId) {
        jdbcTemplate.update("""
                        update menu.product_images
                        set is_primary = false,
                            updated_at = now()
                        where tenant_id = ?
                          and product_id = ?
                          and is_primary = true
                          and deleted_at is null
                        """,
                cafeteriaId,
                productId);
    }

    private boolean hasProductImages(Long cafeteriaId, Long productId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1
                            from menu.product_images
                            where tenant_id = ?
                              and product_id = ?
                              and deleted_at is null
                        )
                        """,
                Boolean.class,
                cafeteriaId,
                productId);
        return Boolean.TRUE.equals(exists);
    }

    private boolean hasPrimaryProductImage(Long cafeteriaId, Long productId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1
                            from menu.product_images
                            where tenant_id = ?
                              and product_id = ?
                              and is_primary = true
                              and is_visible = true
                              and deleted_at is null
                        )
                        """,
                Boolean.class,
                cafeteriaId,
                productId);
        return Boolean.TRUE.equals(exists);
    }

    private boolean isPrimaryProductImage(Long cafeteriaId, Long productId, Long productImageId) {
        Boolean primary = jdbcTemplate.queryForObject("""
                        select coalesce((
                            select is_primary
                            from menu.product_images
                            where tenant_id = ?
                              and product_id = ?
                              and product_image_id = ?
                              and deleted_at is null
                        ), false)
                        """,
                Boolean.class,
                cafeteriaId,
                productId,
                productImageId);
        return Boolean.TRUE.equals(primary);
    }

    private int nextProductImageSortOrder(Long cafeteriaId, Long productId) {
        Integer max = jdbcTemplate.queryForObject("""
                        select coalesce(max(sort_order), -1)
                        from menu.product_images
                        where tenant_id = ?
                          and product_id = ?
                          and deleted_at is null
                        """,
                Integer.class,
                cafeteriaId,
                productId);
        return (max == null ? -1 : max) + 1;
    }

    private Map<String, Object> metadataWithDefaultPrimary(Map<String, Object> metadata, boolean defaultPrimary) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (metadata != null) {
            result.putAll(metadata);
        }
        result.putIfAbsent("primary", defaultPrimary);
        return result;
    }

    private String metadataString(MediaAsset asset, String key, int maxLength) {
        Object value = asset.metadata() == null ? null : asset.metadata().get(key);
        String text = stringOrNull(value);
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private boolean metadataBoolean(MediaAsset asset, String key, boolean fallback) {
        Object value = asset.metadata() == null ? null : asset.metadata().get(key);
        Boolean bool = nullableBoolean(value);
        return bool == null ? fallback : bool;
    }

    private int metadataInt(MediaAsset asset, String key, int fallback) {
        Integer value = nullableInt(asset.metadata() == null ? null : asset.metadata().get(key));
        return value == null ? fallback : value;
    }

    private String normalizeMediaPurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new BadRequestException("Media purpose is required");
        }
        return purpose.trim().toLowerCase(Locale.ROOT).replace('_', '-');
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
                "isDefault", rs.getBoolean("is_default"),
                "sortOrder", rs.getInt("sort_order"),
                "active", rs.getBoolean("is_active")
        );
    }

    private Map<String, Object> catalogAddonRow(ResultSet rs) throws SQLException {
        Map<String, Object> addon = addonRow(rs);
        Long locationAddonId = nullableColumnLong(rs, "location_addon_id");
        Integer priceOverride = nullableColumnInt(rs, "price_override");
        boolean globalAvailable = Boolean.TRUE.equals(addon.get("active"));
        boolean effectiveAvailable = globalAvailable
                && (locationAddonId == null || rs.getBoolean("location_available"));
        addon.put("locationAddonId", locationAddonId);
        addon.put("locationConfigured", locationAddonId != null);
        addon.put("priceOverride", priceOverride);
        addon.put("effectivePrice", priceOverride == null ? addon.get("price") : priceOverride);
        addon.put("effectiveAvailable", effectiveAvailable);
        return addon;
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

    private void clearDefaultAddons(Long cafeteriaId, Long addonGroupId, Long exceptAddonId) {
        List<Object> params = new ArrayList<>();
        params.add(cafeteriaId);
        params.add(addonGroupId);
        String exceptSql = "";
        if (exceptAddonId != null) {
            exceptSql = " and addon_id <> ?";
            params.add(exceptAddonId);
        }
        jdbcTemplate.update("""
                        update menu.lkp_addons
                        set is_default = false,
                            updated_at = now()
                        where tenant_id = ?
                          and addon_group_id = ?
                          and is_default = true
                          and deleted_at is null
                        %s
                        """.formatted(exceptSql),
                params.toArray());
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
        Boolean required = nullableBoolean(request.get("required"));
        validateSelection(required, min, max);
    }

    private void validateSelection(Boolean required, Integer min, Integer max) {
        if (min != null && min < 0) {
            throw new BadRequestException("minSelection must be greater than or equal to zero");
        }
        if (max != null && max < 0) {
            throw new BadRequestException("maxSelection must be greater than or equal to zero");
        }
        if (min != null && max != null && max < min) {
            throw new BadRequestException("maxSelection must be greater than or equal to minSelection");
        }
        if (Boolean.TRUE.equals(required) && min != null && min < 1) {
            throw new BadRequestException("required addon groups must have minSelection greater than zero");
        }
    }

    private String requiredString(Map<String, Object> request, String key) {
        String value = stringOrNull(request.get(key));
        if (value == null) {
            throw new BadRequestException(key + " is required");
        }
        return value;
    }

    private String normalizeCurrencyCode(String currencyCode) {
        String value = stringOrNull(currencyCode);
        if (value == null) {
            return null;
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!value.matches("^[A-Z]{3}$")) {
            throw new BadRequestException("currencyCode must be ISO 4217 format, for example MXN, USD or EUR");
        }
        return value;
    }

    private String normalizeLanguageCode(String languageCode) {
        String value = stringOrNull(languageCode);
        if (value == null) {
            return null;
        }
        String[] parts = value.replace('_', '-').split("-", 2);
        String normalized = parts.length == 1
                ? parts[0].toLowerCase(Locale.ROOT)
                : parts[0].toLowerCase(Locale.ROOT) + "-" + parts[1].toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[a-z]{2}(-[A-Z]{2})?$")) {
            throw new BadRequestException("languageCode must be ISO language format, for example es, en or en-US");
        }
        return normalized;
    }

    private BigDecimal decimalValue(Object value) {
        BigDecimal decimal = nullableBigDecimal(value);
        if (decimal == null) {
            throw new BadRequestException("Decimal value is required");
        }
        if (decimal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Decimal value must be greater than zero");
        }
        return decimal;
    }

    private Integer moneyAmount(Object value) {
        Integer amount = nullableInt(value);
        if (amount == null) {
            throw new BadRequestException("amount is required");
        }
        if (amount < 0) {
            throw new BadRequestException("amount must be greater than or equal to zero");
        }
        return amount;
    }

    private BigDecimal nullableBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : new BigDecimal(text);
    }

    private OffsetDateTime nullableOffsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : OffsetDateTime.parse(text);
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

    private String entityIdKey(String entityIdColumn) {
        return switch (entityIdColumn) {
            case "category_id" -> "categoryId";
            case "product_id" -> "productId";
            case "addon_group_id" -> "addonGroupId";
            case "addon_id" -> "addonId";
            default -> "entityId";
        };
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
