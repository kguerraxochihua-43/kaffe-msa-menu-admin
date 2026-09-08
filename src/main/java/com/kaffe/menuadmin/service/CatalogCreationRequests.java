package com.kaffe.menuadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kaffe.common.exception.BadRequestException;
import com.kaffe.common.exception.ConflictException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Used only inside the catalog transaction; never holds a lock during uploads. */
final class CatalogCreationRequests {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final JdbcTemplate jdbc;

    CatalogCreationRequests(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Claim(UUID requestId, String hash, Long resourceId) {}

    Claim begin(long tenantId, long actorId, String kind, Long parentId, Map<String, Object> body) {
        if (!java.util.Set.of("category", "addon_group", "addon").contains(kind)) throw new IllegalArgumentException("Invalid catalog kind");
        if (body.get("idempotencyKey") == null) return new Claim(null, null, null);
        UUID id;
        try { id = UUID.fromString(body.get("idempotencyKey").toString()); }
        catch (IllegalArgumentException error) {
            throw new BadRequestException("La solicitud de elemento del menú no es válida. Vuelve a abrir el editor.");
        }
        var payload = new LinkedHashMap<>(body);
        payload.remove("idempotencyKey");
        payload.put("_resourceKind", kind);
        payload.put("_parentId", parentId);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new BadRequestException("No pudimos validar los datos del elemento del menú."); }
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                "menu-catalog:" + tenantId + ":" + actorId + ":" + id);
        var rows = jdbc.queryForList("""
                select resource_id, request_hash from menu.catalog_creation_requests
                where tenant_id = ? and actor_user_id = ? and request_id = ?
                """, tenantId, actorId, id);
        if (rows.isEmpty()) return new Claim(id, hash, null);
        var existing = rows.getFirst();
        if (!hash.equals(existing.get("request_hash"))) {
            throw new ConflictException("Esta solicitud ya guardó otro contenido. Actualiza el menú antes de continuar.");
        }
        return new Claim(id, hash, ((Number) existing.get("resource_id")).longValue());
    }

    void complete(long tenantId, long actorId, Claim claim, long resourceId) {
        if (claim.requestId() == null) return;
        jdbc.update("""
                insert into menu.catalog_creation_requests
                    (tenant_id, actor_user_id, request_id, request_hash, resource_id)
                values (?, ?, ?, ?, ?)
                """, tenantId, actorId, claim.requestId(), claim.hash(), resourceId);
    }
}
