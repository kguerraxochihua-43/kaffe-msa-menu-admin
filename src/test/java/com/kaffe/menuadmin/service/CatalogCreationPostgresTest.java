package com.kaffe.menuadmin.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KAFFE_MENU_TEST_DB", matches = "jdbc:postgresql://localhost:[0-9]+/kaffe_menu_test_[a-z0-9_]+")
class CatalogCreationPostgresTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    CatalogCreationRequests requests;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(System.getenv("KAFFE_MENU_TEST_DB"), "postgres",
                System.getenv().getOrDefault("KAFFE_MENU_TEST_PASSWORD", ""));
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        requests = new CatalogCreationRequests(jdbc);
        jdbc.execute("""
                create schema menu;
                create table menu.products(product_id bigint generated always as identity primary key, name text);
                create table menu.catalog_creation_requests(
                  tenant_id bigint not null, actor_user_id bigint not null, request_id uuid not null,
                  request_hash varchar(64) not null, resource_id bigint not null references menu.products,
                  primary key(tenant_id,actor_user_id,request_id));
                """);
    }
    @AfterEach void cleanup() { if (jdbc != null) jdbc.execute("drop schema menu cascade"); }

    Map<String,Object> payload() { return new LinkedHashMap<>(Map.of(
            "idempotencyKey", UUID.randomUUID().toString(), "name", "Latte", "basePrice", 7600,
            "description", "Con miel", "addonGroupIds", List.of(8,9))); }
    long create(long tenant, long actor, Map<String,Object> body) {
        return tx.execute(status -> {
            var claim = requests.begin(tenant, actor, "addon", 7L, body);
            if (claim.resourceId() != null) return claim.resourceId();
            long id = jdbc.queryForObject("insert into menu.products(name) values(?) returning product_id", Long.class, body.get("name"));
            requests.complete(tenant, actor, claim, id);
            return id;
        });
    }
    @Test void concurrentAndLostResponseRetriesCreateOnlyOneProduct() throws Exception {
        var body = payload();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> create(1,1,body));
            var second = executor.submit(() -> create(1,1,body));
            long id = first.get(10,TimeUnit.SECONDS);
            assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(create(1,1,body)).isEqualTo(id);
        }
        assertThat(jdbc.queryForObject("select count(*) from menu.products",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from menu.catalog_creation_requests",Long.class)).isEqualTo(1);
    }
    @Test void changedPayloadDoesNotCreateAnotherProductAndPropertyOrderIsIrrelevant() {
        var body = payload(); long id = create(1,1,body);
        assertThat(create(1,1,new TreeMap<>(body))).isEqualTo(id);
        body.put("basePrice", 9000);
        assertThatThrownBy(() -> create(1,1,body)).hasMessageContaining("otro contenido");
        assertThat(jdbc.queryForObject("select count(*) from menu.products",Long.class)).isEqualTo(1);
    }
    @Test void actorAndTenantHaveIndependentKeys() {
        var body = payload(); long id = create(1,1,body);
        assertThat(create(2,1,body)).isNotEqualTo(id);
        assertThat(create(1,2,body)).isNotEqualTo(id);
    }
    @Test void requestCannotBeReusedForAnotherKindOrParent() {
        var body = payload(); create(1,1,body);
        assertThatThrownBy(() -> tx.execute(s -> requests.begin(1,1,"category",null,body))).hasMessageContaining("otro contenido");
        assertThatThrownBy(() -> tx.execute(s -> requests.begin(1,1,"addon",8L,body))).hasMessageContaining("otro contenido");
    }
    @Test void rollbackDoesNotConsumeTheKey() {
        var body = payload();
        assertThatThrownBy(() -> tx.execute(status -> {
            var claim = requests.begin(1,1,"addon",7L,body);
            long id = jdbc.queryForObject("insert into menu.products(name) values('Latte') returning product_id", Long.class);
            requests.complete(1,1,claim,id);
            throw new IllegalStateException("simulate transaction rollback");
        })).hasMessageContaining("rollback");
        create(1,1,body);
        assertThat(jdbc.queryForObject("select count(*) from menu.products",Long.class)).isEqualTo(1);
    }
    @Test void invalidKeyFailsBeforeCreationAndLegacyClientsRemainCompatible() {
        var body = payload(); body.put("idempotencyKey","not-a-uuid");
        assertThatThrownBy(() -> create(1,1,body)).hasMessageContaining("solicitud");
        body.remove("idempotencyKey");
        create(1,1,body);
        assertThat(jdbc.queryForObject("select count(*) from menu.catalog_creation_requests",Long.class)).isZero();
    }
}
