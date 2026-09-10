package com.kaffe.menuadmin.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaffe.common.exception.*;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.*;
import com.kaffe.menuadmin.service.MenuAdminService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real draft SQL and rollback; existing catalog CRUD is separately covered by its tests. */
@EnabledIfEnvironmentVariable(named = "KAFFE_MENU_TEST_DB", matches = "jdbc:postgresql://localhost:[0-9]+/kaffe_menu_test_[a-z0-9_]+")
class AiMenuDraftPostgresTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    AiMenuDraftService service;
    MenuImportAiProvider provider;
    MenuAdminService catalog;
    ObjectMapper mapper = new ObjectMapper();
    boolean verified;
    boolean failProduct;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(System.getenv("KAFFE_MENU_TEST_DB"), "postgres",
                System.getenv().getOrDefault("KAFFE_MENU_TEST_PASSWORD", ""));
        jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).startsWith("kaffe_menu_test_");
        verified = true;
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
                create schema menu;
                create table menu.ai_menu_import_drafts (
                  ai_menu_import_draft_id uuid primary key, tenant_id bigint not null, actor_user_id bigint not null,
                  status_code text not null, source_sha256 text not null, idempotency_key_hash text not null,
                  provider_code text, model_code text, schema_code text, failure_code text,
                  draft_payload jsonb, published_payload jsonb, version integer not null default 0,
                  created_at timestamptz default now(), updated_at timestamptz default now(),
                  published_at timestamptz, discarded_at timestamptz, unique(actor_user_id,idempotency_key_hash));
                create table menu.categories(category_id bigint generated always as identity primary key,
                  tenant_id bigint, name text, deleted_at timestamptz);
                create table menu.products(product_id bigint generated always as identity primary key,
                  tenant_id bigint, category_id bigint, name text, deleted_at timestamptz);
                create table menu.catalog_writes(id bigint generated always as identity primary key, kind text, payload jsonb);
                insert into menu.categories(tenant_id,name) values(1,'Cafés');
                insert into menu.products(tenant_id,category_id,name) values(1,1,'Latte');
                """);
        catalog = mock(MenuAdminService.class);
        when(catalog.requireGlobalMenuWriteAccess(anyLong())).thenReturn(7L);
        when(catalog.createMenu(anyLong(), anyMap())).thenAnswer(call -> Map.of("menuId", write("menu", call.getArgument(1))));
        when(catalog.createCategory(anyLong(), anyMap())).thenAnswer(call -> {
            Map<String,Object> body = call.getArgument(1); write("category", body);
            return Map.of("categoryId", jdbc.queryForObject(
                    "insert into menu.categories(tenant_id,name) values(?,?) returning category_id", Long.class,
                    call.getArgument(0), body.get("name")));
        });
        when(catalog.createAddonGroup(anyLong(), anyMap())).thenAnswer(call -> Map.of("addonGroupId", write("group", call.getArgument(1))));
        when(catalog.createAddon(anyLong(), anyLong(), anyMap())).thenAnswer(call -> Map.of("addonId", write("option", call.getArgument(2))));
        when(catalog.createProduct(anyLong(), anyMap())).thenAnswer(call -> {
            if (failProduct) throw new BadRequestException("Simulated catalog validation failure");
            Map<String,Object> body = call.getArgument(1); write("product", body);
            return Map.of("productId", jdbc.queryForObject(
                    "insert into menu.products(tenant_id,category_id,name) values(?,?,?) returning product_id", Long.class,
                    call.getArgument(0), body.get("categoryId"), body.get("name")));
        });
        provider = mock(MenuImportAiProvider.class);
        when(provider.isReady()).thenReturn(true);
        when(provider.providerCode()).thenReturn("test");
        when(provider.modelCode()).thenReturn("test");
        when(provider.extract(nullable(MenuImageInput.class), anyString(), anyString())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return payload();
        });
        service = new AiMenuDraftService(jdbc, mapper, catalog, provider);
    }

    @AfterEach void cleanup() { if (verified) jdbc.execute("drop schema menu cascade"); }

    @Test void generationIsReadOnlyForCatalogAndRetriesRecoverExactlyOneDraft() {
        var first = create("retry", "Latte 60 pesos");
        assertThat(create("retry", "Latte 60 pesos").draftId()).isEqualTo(first.draftId());
        assertThat(first.draft().sourceText()).isEqualTo("Latte 60 pesos");
        assertThat(count("menu.catalog_writes")).isZero();
        verify(provider, times(1)).extract(nullable(MenuImageInput.class), anyString(), anyString());
        assertThatThrownBy(() -> create("retry", "Latte 80 pesos")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.create(2L, "retry", null, null, "Latte 60 pesos"))
                .isInstanceOf(ConflictException.class);
    }

    @Test void saveCreatesSeparateInactiveMenuAndAddonsExactlyOnce() {
        var draft = create("publish", "Latte 60 pesos con tamaños");
        var published = tx.execute(s -> service.publish(1L, draft.draftId(), new PublishRequest(0, true)));
        var replay = tx.execute(s -> service.publish(1L, draft.draftId(), new PublishRequest(0, true)));
        assertThat(replay.version()).isEqualTo(published.version());
        assertThat(published.publication().get("active")).isEqualTo(false);
        assertThat(jdbc.queryForObject("select payload->>'active' from menu.catalog_writes where kind='menu'", String.class)).isEqualTo("false");
        assertThat(jdbc.queryForObject("select payload ? 'menuIds' from menu.catalog_writes where kind='category'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from menu.products where category_id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select payload->>'price' from menu.catalog_writes where kind='option' order by id", String.class))
                .containsExactly("0", "1500");
        assertThat(count("menu.products")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select jsonb_array_length(payload->'addonGroupIds') from menu.catalog_writes where kind='product'", Integer.class)).isEqualTo(1);
    }

    @Test void catalogFailureRollsBackMenuCategoriesAddonsAndPublication() {
        var draft = create("rollback", "Latte 60 pesos");
        failProduct = true;
        assertThatThrownBy(() -> tx.execute(s -> service.publish(1L, draft.draftId(), new PublishRequest(0, true))))
                .isInstanceOf(BadRequestException.class);
        assertThat(count("menu.catalog_writes")).isZero();
        assertThat(count("menu.categories")).isEqualTo(1);
        assertThat(service.get(1L, draft.draftId()).status()).isEqualTo("ready");
        failProduct = false;
        tx.execute(s -> service.publish(1L, draft.draftId(), new PublishRequest(0, true)));
        assertThat(count("menu.products")).isEqualTo(2);
    }

    @Test void staleEditsAndRevokedPermissionCannotPublish() {
        var draft = create("versions", "Latte 60 pesos");
        service.update(1L, draft.draftId(), new UpdateRequest(0, payload()));
        assertThatThrownBy(() -> tx.execute(s -> service.publish(1L, draft.draftId(), new PublishRequest(0, true))))
                .isInstanceOf(ConflictException.class);
        when(catalog.requireGlobalMenuWriteAccess(1L)).thenThrow(new ForbiddenException("Revoked"));
        assertThatThrownBy(() -> service.get(1L, draft.draftId())).isInstanceOf(ForbiddenException.class);
        assertThat(count("menu.catalog_writes")).isZero();
    }

    @Test void failureIsRecordedWithoutStoringMediaOrPublishingPartialCatalog() {
        when(provider.extract(nullable(MenuImageInput.class), anyString(), anyString())).thenThrow(new MenuImportAiException("timeout"));
        assertThatThrownBy(() -> create("failed", "Latte 60 pesos")).isInstanceOf(BusinessRuleException.class);
        assertThat(jdbc.queryForObject("select status_code from menu.ai_menu_import_drafts", String.class)).isEqualTo("failed");
        assertThat(count("menu.catalog_writes")).isZero();
    }

    @Test void providerIsNeverInvokedWithoutGlobalWriteAccess() {
        when(catalog.requireGlobalMenuWriteAccess(1L)).thenThrow(new ForbiddenException("No permission"));
        assertThatThrownBy(() -> create("denied", "Latte 60 pesos")).isInstanceOf(ForbiddenException.class);
        verify(provider, never()).extract(nullable(MenuImageInput.class), anyString(), anyString());
    }

    DraftResponse create(String key, String text) { return service.create(1L, key, null, null, text); }
    long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    long write(String kind, Map<String,Object> payload) throws Exception {
        return jdbc.queryForObject("insert into menu.catalog_writes(kind,payload) values(?,cast(? as jsonb)) returning id",
                Long.class, kind, mapper.writeValueAsString(payload));
    }
    DraftPayload payload() {
        return new DraftPayload("Cafés de la tarde", "MXN", List.of(new DraftCategory("Cafés", "De especialidad", 0,
                List.of(new DraftProduct("Latte", "Con leche", 6000, "$60", 0, false, List.of(), List.of(
                        new DraftOptionGroup("Tamaño", true, 1, 1, List.of(new DraftOption("Chico", 0, true),
                                new DraftOption("Grande", 1500, false)))))))), List.of());
    }
}
