package com.kaffe.menuadmin.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaffe.common.exception.BadRequestException;
import com.kaffe.common.exception.BusinessRuleException;
import com.kaffe.common.exception.ConflictException;
import com.kaffe.common.exception.ResourceNotFoundException;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftCategory;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftPayload;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftProduct;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftResponse;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.PublishRequest;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.UpdateRequest;
import com.kaffe.menuadmin.service.MenuAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiMenuDraftService {

    private static final int MIN_IMAGE_BYTES = 1_024;
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final String SCHEMA_CODE = "kaffe_menu_assisted_v2";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MenuAdminService menuAdminService;
    private final MenuImportAiProvider provider;

    public DraftResponse create(Long cafeteriaId, String idempotencyKey, byte[] bytes) {
        return create(cafeteriaId, idempotencyKey, bytes, null, null);
    }

    public DraftResponse create(Long cafeteriaId, String idempotencyKey, byte[] bytes, byte[] audio, String text) {
        long actorUserId = menuAdminService.requireGlobalMenuWriteAccess(cafeteriaId);
        if (!provider.isReady()) {
            throw new BusinessRuleException(
                    "La digitalización de menú todavía no está habilitada"
            );
        }
        validateIdempotencyKey(idempotencyKey);
        MenuImageInput image = bytes == null ? null : validateImage(bytes);
        if (audio != null) MenuAudioInput.validate(audio);
        String sourceText = text == null ? "" : text.strip();
        if (sourceText.length() > 12_000 || (image == null && audio == null && sourceText.isEmpty())
                || (bytes != null && audio != null && (long) bytes.length + audio.length > 5_700_000)) {
            throw new BadRequestException("Agrega una foto, un dictado o un texto de hasta 12000 caracteres");
        }
        String sourceHash = sha256("image=" + (bytes == null ? "" : sha256(bytes))
                + ";audio=" + (audio == null ? "" : sha256(audio)) + ";text=" + sourceText);
        String idempotencyHash = sha256(idempotencyKey.trim());
        DraftResponse existing = findByIdempotency(actorUserId, idempotencyHash);
        if (existing != null) return requireSameInput(existing, cafeteriaId, sourceHash);
        enforceRateLimit(actorUserId);

        UUID draftId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    insert into menu.ai_menu_import_drafts (
                        ai_menu_import_draft_id, tenant_id, actor_user_id, status_code,
                        source_sha256, idempotency_key_hash, provider_code, model_code, schema_code
                    ) values (?, ?, ?, 'processing', ?, ?, ?, ?, ?)
                    """,
                    draftId,
                    cafeteriaId,
                    actorUserId,
                    sourceHash,
                    idempotencyHash,
                    provider.providerCode(),
                    provider.modelCode(),
                    SCHEMA_CODE);
        } catch (DuplicateKeyException ex) {
            DraftResponse concurrent = findByIdempotency(actorUserId, idempotencyHash);
            if (concurrent != null) return requireSameInput(concurrent, cafeteriaId, sourceHash);
            throw ex;
        }

        try {
            if (audio != null) {
                String transcript = provider.transcribe(audio);
                sourceText = sourceText.isEmpty() ? transcript : sourceText + "\n\nDictado:\n" + transcript;
            }
            DraftPayload recognized = provider.extract(
                    image,
                    sourceText,
                    sha256("kaffe-menu:%d:%d".formatted(cafeteriaId, actorUserId))
            );
            if (recognized == null) throw new MenuImportAiException("No se reconoció un menú");
            DraftPayload normalized = AiMenuDraftRules.normalize(new DraftPayload(recognized.title(),
                    recognized.currencyCode(), recognized.categories(), recognized.warnings(), sourceText));
            jdbcTemplate.update("""
                    update menu.ai_menu_import_drafts
                    set status_code = 'ready',
                        draft_payload = cast(? as jsonb),
                        updated_at = now()
                    where ai_menu_import_draft_id = ?
                      and status_code = 'processing'
                    """, writeJson(normalized), draftId);
            return get(cafeteriaId, draftId);
        } catch (RuntimeException ex) {
            jdbcTemplate.update("""
                    update menu.ai_menu_import_drafts
                    set status_code = 'failed',
                        failure_code = 'recognition_failed',
                        updated_at = now()
                    where ai_menu_import_draft_id = ?
                      and status_code = 'processing'
                    """, draftId);
            throw new BusinessRuleException(
                    "No pudimos preparar el borrador. Revisa la foto o el dictado y vuelve a intentar."
            );
        }
    }

    @Transactional(readOnly = true)
    public List<DraftResponse> list(Long cafeteriaId, int requestedLimit) {
        menuAdminService.requireGlobalMenuWriteAccess(cafeteriaId);
        int limit = Math.max(1, Math.min(requestedLimit, 20));
        return jdbcTemplate.query("""
                        select *
                        from menu.ai_menu_import_drafts
                        where tenant_id = ?
                          and status_code in ('ready', 'published')
                        order by updated_at desc
                        limit ?
                        """,
                (rs, rowNum) -> row(rs),
                cafeteriaId,
                limit);
    }

    @Transactional(readOnly = true)
    public DraftResponse get(Long cafeteriaId, UUID draftId) {
        menuAdminService.requireGlobalMenuWriteAccess(cafeteriaId);
        List<DraftResponse> rows = jdbcTemplate.query("""
                        select *
                        from menu.ai_menu_import_drafts
                        where tenant_id = ?
                          and ai_menu_import_draft_id = ?
                        """,
                (rs, rowNum) -> row(rs),
                cafeteriaId,
                draftId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("El borrador del menú no existe");
        }
        return rows.getFirst();
    }

    @Transactional
    public DraftResponse update(Long cafeteriaId, UUID draftId, UpdateRequest request) {
        menuAdminService.requireGlobalMenuWriteAccess(cafeteriaId);
        if (request == null || request.expectedVersion() < 0) {
            throw new BadRequestException("La versión del borrador es obligatoria");
        }
        DraftPayload normalized = AiMenuDraftRules.normalize(request.draft());
        int updated = jdbcTemplate.update("""
                update menu.ai_menu_import_drafts
                set draft_payload = cast(? as jsonb),
                    version = version + 1,
                    updated_at = now()
                where tenant_id = ?
                  and ai_menu_import_draft_id = ?
                  and status_code = 'ready'
                  and version = ?
                """,
                writeJson(normalized),
                cafeteriaId,
                draftId,
                request.expectedVersion());
        if (updated == 0) throwDraftConflictOrMissing(cafeteriaId, draftId);
        return get(cafeteriaId, draftId);
    }

    @Transactional
    public DraftResponse publish(Long cafeteriaId, UUID draftId, PublishRequest request) {
        menuAdminService.requireGlobalMenuWriteAccess(cafeteriaId);
        if (request == null || request.expectedVersion() < 0) {
            throw new BadRequestException("La versión del borrador es obligatoria");
        }
        DraftResponse locked = lock(cafeteriaId, draftId);
        if ("published".equals(locked.status())) return locked;
        if (!"ready".equals(locked.status()) || locked.version() != request.expectedVersion()) {
            throw new ConflictException("El borrador cambió. Actualízalo antes de publicar.");
        }
        DraftPayload payload = AiMenuDraftRules.normalize(locked.draft());
        AiMenuDraftRules.requirePublishable(payload);
        if (!"MXN".equals(payload.currencyCode())) {
            throw new BadRequestException(
                    "Por ahora sólo se pueden publicar borradores con precios en MXN"
            );
        }
        jdbcTemplate.query(
                "select pg_advisory_xact_lock(hashtextextended('kaffe-ai-menu:' || ?, 0))",
                resultSet -> null,
                cafeteriaId.toString()
        );

        Long separateMenuId = null;
        if (request.createSeparateMenu()) {
            String name = payload.title();
            if (name == null || name.isBlank()) {
                throw new BadRequestException("Escribe el nombre del nuevo menú antes de guardarlo");
            }
            // New menus are inactive until their locations/schedule are reviewed in the existing editor.
            Boolean taken = jdbcTemplate.queryForObject("""
                    select exists(select 1 from menu.menus
                      where tenant_id = ? and lower(name) = lower(?) and deleted_at is null)
                    """, Boolean.class, cafeteriaId, name);
            if (Boolean.TRUE.equals(taken)) throw menuNameTaken();
            try {
                separateMenuId = number(menuAdminService.createMenu(cafeteriaId, Map.of(
                        "name", name, "active", false, "global", true, "categoryIds", List.of())).get("menuId"));
            } catch (DuplicateKeyException ex) {
                throw menuNameTaken();
            }
        }

        List<Map<String, Object>> publicationCategories = new ArrayList<>();
        for (DraftCategory category : payload.categories()) {
            long categoryId = separateMenuId == null ? findCategoryId(cafeteriaId, category.name()) : 0;
            boolean categoryCreated = false;
            if (categoryId == 0) {
                Map<String, Object> categoryRequest = new LinkedHashMap<>(Map.of(
                        "name", category.name(),
                        "description", category.description() == null ? "" : category.description(),
                        "sortOrder", category.sortOrder(),
                        "active", true
                ));
                if (separateMenuId != null) categoryRequest.put("menuIds", List.of(separateMenuId));
                Map<String, Object> created = menuAdminService.createCategory(cafeteriaId, categoryRequest);
                categoryId = number(created.get("categoryId"));
                categoryCreated = true;
            }
            List<Long> productIds = new ArrayList<>();
            for (DraftProduct product : category.products()) {
                ensureProductNameAvailable(cafeteriaId, categoryId, product.name());
                List<Long> optionGroupIds = new ArrayList<>();
                for (var group : product.optionGroups()) {
                    long groupId;
                    try {
                        groupId = number(menuAdminService.createAddonGroup(cafeteriaId, Map.of(
                                "name", availableOptionGroupName(cafeteriaId, group.name(), product.name()),
                                "required", group.required(), "minSelection", group.minSelection(),
                                "maxSelection", group.maxSelection(), "active", true,
                                "sortOrder", optionGroupIds.size())).get("addonGroupId"));
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("Las opciones del catálogo cambiaron. Vuelve a guardar el menú.");
                    }
                    for (int index = 0; index < group.options().size(); index++) {
                        var option = group.options().get(index);
                        menuAdminService.createAddon(cafeteriaId, groupId, Map.of(
                                "name", option.name(), "price", option.priceMinor(), "isDefault", option.isDefault(),
                                "sortOrder", index, "active", true));
                    }
                    optionGroupIds.add(groupId);
                }
                Map<String, Object> created = menuAdminService.createProduct(cafeteriaId, Map.of(
                        "categoryId", categoryId,
                        "name", product.name(),
                        "description", product.description() == null ? "" : product.description(),
                        "basePrice", product.basePriceMinor(),
                        "featured", false,
                        "available", true,
                        "sortOrder", product.sortOrder(),
                        "addonGroupIds", optionGroupIds
                ));
                productIds.add(number(created.get("productId")));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("categoryId", categoryId);
            result.put("categoryName", category.name());
            result.put("categoryCreated", categoryCreated);
            result.put("productIds", List.copyOf(productIds));
            publicationCategories.add(Map.copyOf(result));
        }
        Map<String, Object> publication = new LinkedHashMap<>();
        if (separateMenuId != null) {
            publication.put("menuId", separateMenuId);
            publication.put("active", false);
        }
        publication.put("currencyCode", payload.currencyCode());
        publication.put("categories", List.copyOf(publicationCategories));
        publication.put("categoryCount", publicationCategories.size());
        publication.put("productCount", payload.categories().stream()
                .mapToInt(category -> category.products().size()).sum());

        int updated = jdbcTemplate.update("""
                update menu.ai_menu_import_drafts
                set status_code = 'published',
                    published_payload = cast(? as jsonb),
                    published_at = now(),
                    version = version + 1,
                    updated_at = now()
                where tenant_id = ?
                  and ai_menu_import_draft_id = ?
                  and status_code = 'ready'
                  and version = ?
                """,
                writeJson(publication),
                cafeteriaId,
                draftId,
                request.expectedVersion());
        if (updated == 0) {
            throw new ConflictException("El borrador cambió durante la publicación");
        }
        return get(cafeteriaId, draftId);
    }

    @Transactional
    public DraftResponse discard(Long cafeteriaId, UUID draftId, int expectedVersion) {
        menuAdminService.requireGlobalMenuWriteAccess(cafeteriaId);
        if (expectedVersion < 0) {
            throw new BadRequestException("La versión del borrador es obligatoria");
        }
        int updated = jdbcTemplate.update("""
                update menu.ai_menu_import_drafts
                set status_code = 'discarded',
                    discarded_at = now(),
                    version = version + 1,
                    updated_at = now()
                where tenant_id = ?
                  and ai_menu_import_draft_id = ?
                  and status_code = 'ready'
                  and version = ?
                """, cafeteriaId, draftId, expectedVersion);
        if (updated == 0) throwDraftConflictOrMissing(cafeteriaId, draftId);
        return get(cafeteriaId, draftId);
    }

    private DraftResponse requireCompleted(DraftResponse response) {
        if ("processing".equals(response.status())) {
            throw new ConflictException("El menú ya se está preparando. Intenta recuperar el borrador en un momento.");
        }
        if ("failed".equals(response.status())) {
            throw new BusinessRuleException(
                    "No pudimos preparar ese borrador. Vuelve a intentar con la foto, voz o texto."
            );
        }
        return response;
    }

    private DraftResponse requireSameInput(DraftResponse response, Long cafeteriaId, String sourceHash) {
        if (response.tenantId() != cafeteriaId) {
            throw new ConflictException("Esta solicitud pertenece a otro comercio");
        }
        Boolean same = jdbcTemplate.queryForObject("""
                select source_sha256 = ? from menu.ai_menu_import_drafts
                where tenant_id = ? and ai_menu_import_draft_id = ?
                """, Boolean.class, sourceHash, cafeteriaId, response.draftId());
        if (!Boolean.TRUE.equals(same)) {
            throw new ConflictException("El contenido cambió. Inicia un nuevo borrador para conservar los cambios.");
        }
        return requireCompleted(response);
    }

    private DraftResponse findByIdempotency(long actorUserId, String idempotencyHash) {
        List<DraftResponse> rows = jdbcTemplate.query("""
                        select *
                        from menu.ai_menu_import_drafts
                        where actor_user_id = ?
                          and idempotency_key_hash = ?
                        """,
                (rs, rowNum) -> row(rs),
                actorUserId,
                idempotencyHash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void enforceRateLimit(long actorUserId) {
        Integer minute = jdbcTemplate.queryForObject("""
                select count(*)
                from menu.ai_menu_import_drafts
                where actor_user_id = ?
                  and created_at >= now() - interval '1 minute'
                """, Integer.class, actorUserId);
        Integer day = jdbcTemplate.queryForObject("""
                select count(*)
                from menu.ai_menu_import_drafts
                where actor_user_id = ?
                  and created_at >= now() - interval '1 day'
                """, Integer.class, actorUserId);
        if ((minute != null && minute >= 5) || (day != null && day >= 30)) {
            throw new ConflictException(
                    "Alcanzaste el límite temporal de digitalizaciones. Intenta más tarde."
            );
        }
    }

    private DraftResponse lock(Long cafeteriaId, UUID draftId) {
        List<DraftResponse> rows = jdbcTemplate.query("""
                        select *
                        from menu.ai_menu_import_drafts
                        where tenant_id = ?
                          and ai_menu_import_draft_id = ?
                        for update
                        """,
                (rs, rowNum) -> row(rs),
                cafeteriaId,
                draftId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("El borrador del menú no existe");
        }
        return rows.getFirst();
    }

    private void throwDraftConflictOrMissing(Long cafeteriaId, UUID draftId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from menu.ai_menu_import_drafts
                where tenant_id = ? and ai_menu_import_draft_id = ?
                """, Integer.class, cafeteriaId, draftId);
        if (count == null || count == 0) {
            throw new ResourceNotFoundException("El borrador del menú no existe");
        }
        throw new ConflictException("El borrador cambió. Actualízalo e intenta nuevamente.");
    }

    private long findCategoryId(long cafeteriaId, String name) {
        List<Long> ids = jdbcTemplate.queryForList("""
                select category_id
                from menu.categories
                where tenant_id = ?
                  and lower(name) = lower(?)
                  and deleted_at is null
                order by category_id
                limit 1
                """, Long.class, cafeteriaId, name);
        return ids.isEmpty() ? 0 : ids.getFirst();
    }

    private ConflictException menuNameTaken() {
        return new ConflictException("Ya tienes un menú con ese nombre. Cambia el nombre del borrador para guardarlo aparte.");
    }

    private String availableOptionGroupName(long tenantId, String name, String productName) {
        // Groups have a tenant-wide unique name. Never reuse or overwrite an
        // existing live group's options: qualify the new name with the product.
        String base = shortLabel(name + " · " + productName, 90);
        String candidate = name;
        for (int attempt = 0; attempt < 1000; attempt++) {
            Boolean taken = jdbcTemplate.queryForObject("""
                    select exists(select 1 from menu.lkp_addon_groups
                      where tenant_id = ? and lower(name) = lower(?))
                    """, Boolean.class, tenantId, candidate);
            if (!Boolean.TRUE.equals(taken)) return candidate;
            candidate = attempt == 0 ? base : base + " (" + (attempt + 1) + ")";
        }
        throw new ConflictException("Hay demasiadas opciones con ese nombre. Renombra el grupo en el borrador.");
    }

    private String shortLabel(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end).stripTrailing();
    }

    private void ensureProductNameAvailable(long cafeteriaId, long categoryId, String name) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1 from menu.products
                    where tenant_id = ?
                      and category_id = ?
                      and lower(name) = lower(?)
                      and deleted_at is null
                )
                """, Boolean.class, cafeteriaId, categoryId, name);
        if (Boolean.TRUE.equals(exists)) {
            throw new ConflictException(
                    "Ya existe “%s” en esa categoría. Renómbralo o elimínalo del borrador."
                            .formatted(name)
            );
        }
    }

    private DraftResponse row(ResultSet rs) throws SQLException {
        return new DraftResponse(
                rs.getObject("ai_menu_import_draft_id", UUID.class),
                rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"),
                rs.getString("status_code"),
                rs.getInt("version"),
                readPayload(rs.getString("draft_payload")),
                readMap(rs.getString("published_payload")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getObject("discarded_at", OffsetDateTime.class)
        );
    }

    private DraftPayload readPayload(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, DraftPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored AI menu draft is invalid", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored AI menu publication is invalid", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            // Leave room for jsonb whitespace; reject before the database size constraint.
            if (json.getBytes(StandardCharsets.UTF_8).length > 400_000) {
                throw new BadRequestException("El menú es muy extenso. Divídelo en dos borradores.");
            }
            return json;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to encode AI menu draft", ex);
        }
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new BadRequestException("La clave de idempotencia es obligatoria");
        }
    }

    static MenuImageInput validateImage(byte[] bytes) {
        if (bytes == null || bytes.length < MIN_IMAGE_BYTES || bytes.length > MAX_IMAGE_BYTES) {
            throw new BadRequestException("La fotografía debe pesar entre 1 KB y 5 MB");
        }
        if (isJpeg(bytes)) return new MenuImageInput(bytes, "image/jpeg");
        if (isPng(bytes)) return new MenuImageInput(bytes, "image/png");
        if (isWebp(bytes)) return new MenuImageInput(bytes, "image/webp");
        throw new BadRequestException("Usa una fotografía JPEG, PNG o WebP");
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff;
    }

    private static boolean isPng(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) return false;
        }
        return true;
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
