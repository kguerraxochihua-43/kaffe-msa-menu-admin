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
    private static final String SCHEMA_CODE = "kaffe_menu_photo_v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MenuAdminService menuAdminService;
    private final MenuImportAiProvider provider;

    public DraftResponse create(Long cafeteriaId, String idempotencyKey, byte[] bytes) {
        long actorUserId = menuAdminService.requireGlobalMenuWriteAccess(cafeteriaId);
        if (!provider.isReady()) {
            throw new BusinessRuleException(
                    "La digitalización de menú todavía no está habilitada"
            );
        }
        validateIdempotencyKey(idempotencyKey);
        MenuImageInput image = validateImage(bytes);
        String idempotencyHash = sha256(idempotencyKey.trim());
        DraftResponse existing = findByIdempotency(actorUserId, idempotencyHash);
        if (existing != null) return requireCompleted(existing);
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
                    sha256(bytes),
                    idempotencyHash,
                    provider.providerCode(),
                    provider.modelCode(),
                    SCHEMA_CODE);
        } catch (DuplicateKeyException ex) {
            DraftResponse concurrent = findByIdempotency(actorUserId, idempotencyHash);
            if (concurrent != null) return requireCompleted(concurrent);
            throw ex;
        }

        try {
            DraftPayload recognized = provider.extract(
                    image,
                    sha256("kaffe-menu:%d:%d".formatted(cafeteriaId, actorUserId))
            );
            DraftPayload normalized = AiMenuDraftRules.normalize(recognized);
            jdbcTemplate.update("""
                    update menu.ai_menu_import_drafts
                    set status_code = 'ready',
                        draft_payload = cast(? as jsonb),
                        updated_at = now()
                    where ai_menu_import_draft_id = ?
                      and status_code = 'processing'
                    """, writeJson(normalized), draftId);
            return get(cafeteriaId, draftId);
        } catch (MenuImportAiException | BadRequestException ex) {
            jdbcTemplate.update("""
                    update menu.ai_menu_import_drafts
                    set status_code = 'failed',
                        failure_code = 'recognition_failed',
                        updated_at = now()
                    where ai_menu_import_draft_id = ?
                      and status_code = 'processing'
                    """, draftId);
            throw new BusinessRuleException(
                    "No pudimos leer el menú con suficiente seguridad. Toma otra foto con buena luz."
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
        AiMenuDraftRules.requirePublishable(locked.draft());
        if (!"MXN".equals(locked.draft().currencyCode())) {
            throw new BadRequestException(
                    "Por ahora sólo se pueden publicar borradores con precios en MXN"
            );
        }
        jdbcTemplate.query(
                "select pg_advisory_xact_lock(hashtextextended('kaffe-ai-menu:' || ?, 0))",
                resultSet -> null,
                cafeteriaId.toString()
        );

        List<Map<String, Object>> publicationCategories = new ArrayList<>();
        for (DraftCategory category : locked.draft().categories()) {
            long categoryId = findCategoryId(cafeteriaId, category.name());
            boolean categoryCreated = false;
            if (categoryId == 0) {
                Map<String, Object> created = menuAdminService.createCategory(cafeteriaId, Map.of(
                        "name", category.name(),
                        "description", category.description() == null ? "" : category.description(),
                        "sortOrder", category.sortOrder(),
                        "active", true
                ));
                categoryId = number(created.get("categoryId"));
                categoryCreated = true;
            }
            List<Long> productIds = new ArrayList<>();
            for (DraftProduct product : category.products()) {
                ensureProductNameAvailable(cafeteriaId, categoryId, product.name());
                Map<String, Object> created = menuAdminService.createProduct(cafeteriaId, Map.of(
                        "categoryId", categoryId,
                        "name", product.name(),
                        "description", product.description() == null ? "" : product.description(),
                        "basePrice", product.basePriceMinor(),
                        "featured", false,
                        "available", true,
                        "sortOrder", product.sortOrder()
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
        publication.put("currencyCode", locked.draft().currencyCode());
        publication.put("categories", List.copyOf(publicationCategories));
        publication.put("categoryCount", publicationCategories.size());
        publication.put("productCount", locked.draft().categories().stream()
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
            throw new ConflictException("La fotografía ya se está procesando");
        }
        if ("failed".equals(response.status())) {
            throw new BusinessRuleException(
                    "No pudimos leer esa fotografía. Toma otra con buena luz."
            );
        }
        return response;
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
            return objectMapper.writeValueAsString(value);
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
