package com.kaffe.menuadmin.service;

import com.kaffe.common.security.CurrentUserProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "KAFFE_MENU_TEST_DB", matches = "jdbc:postgresql://localhost:[0-9]+/kaffe_menu_test_[a-z0-9_]+")
class CatalogEditingPostgresTest {
    JdbcTemplate jdbc;
    MenuAdminService service;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("KAFFE_MENU_TEST_DB"), "postgres",
                System.getenv().getOrDefault("KAFFE_MENU_TEST_PASSWORD", "")));
        jdbc.execute("""
            create schema auth; create schema catalog; create schema menu;
            create table auth.tenant_user_role_assignments(user_id bigint,tenant_id bigint,tenant_role_id bigint,
              location_id bigint,status_id int,is_default boolean,created_at timestamptz default now());
            create table auth.tenant_roles(tenant_role_id bigint,tenant_id bigint,code text,status_id int);
            create table auth.tenant_role_policies(tenant_role_id bigint,policy_id bigint,enabled boolean);
            create table catalog.lkp_policies(policy_id bigint,code text,status_id int);
            insert into auth.tenant_user_role_assignments(user_id,tenant_id,tenant_role_id,status_id,is_default) values(1,1,1,1,true);
            insert into auth.tenant_roles values(1,1,'owner',1);
            insert into auth.tenant_role_policies values(1,1,true);
            insert into catalog.lkp_policies values(1,'menu:write',1);
            create table menu.categories(category_id bigint,tenant_id bigint,parent_category_id bigint,name text,
              description text,sort_order int default 0,is_active boolean default true,deleted_at timestamptz,updated_at timestamptz);
            create table menu.lkp_addon_groups(addon_group_id bigint,tenant_id bigint,name text,description text,
              min_selection int,max_selection int,is_required boolean,sort_order int default 0,is_active boolean default true,
              deleted_at timestamptz,updated_at timestamptz);
            create table menu.lkp_addons(addon_id bigint,tenant_id bigint,addon_group_id bigint,name text,description text,
              price int,is_default boolean default false,sort_order int default 0,is_active boolean default true,
              deleted_at timestamptz,updated_at timestamptz);
            insert into menu.categories(category_id,tenant_id,name,description) values(1,1,'Bebidas','Anterior');
            insert into menu.lkp_addon_groups(addon_group_id,tenant_id,name,description,min_selection,max_selection,is_required)
              values(1,1,'Extras','Anterior',0,3,false);
            insert into menu.lkp_addons(addon_id,tenant_id,addon_group_id,name,description,price) values(1,1,1,'Avena','Anterior',1400);
            """);
        var user = mock(CurrentUserProvider.class);
        when(user.requireUserId()).thenReturn(1L);
        service = new MenuAdminService(jdbc,user,null,null);
    }
    @AfterEach void cleanup() {
        if(jdbc != null) jdbc.execute("drop schema menu cascade; drop schema auth cascade; drop schema catalog cascade");
    }
    @Test void explicitNullClearsDescriptionsWhileOmittedDescriptionsRemain() {
        assertThat(service.updateCategory(1L,1L,Map.of("name","Bebidas nuevas"))).containsEntry("description","Anterior");
        assertThat(service.updateAddonGroup(1L,1L,Map.of("name","Extras nuevos"))).containsEntry("description","Anterior");
        assertThat(service.updateAddon(1L,1L,1L,Map.of("name","Avena nueva"))).containsEntry("description","Anterior");
        var clear = new LinkedHashMap<String,Object>(); clear.put("description",null);
        assertThat(service.updateCategory(1L,1L,clear).get("description")).isNull();
        assertThat(service.updateAddonGroup(1L,1L,clear).get("description")).isNull();
        assertThat(service.updateAddon(1L,1L,1L,clear).get("description")).isNull();
        assertThat(jdbc.queryForObject("select description from menu.categories where category_id=1",String.class)).isNull();
        assertThat(jdbc.queryForObject("select description from menu.lkp_addon_groups where addon_group_id=1",String.class)).isNull();
        assertThat(jdbc.queryForObject("select description from menu.lkp_addons where addon_id=1",String.class)).isNull();
        assertThat(jdbc.queryForObject("select price from menu.lkp_addons where addon_id=1",Integer.class)).isEqualTo(1400);
    }
    @Test void removingOptionalMaximumActuallyClearsItButDoesNotChangeTheMinimum() {
        var clear = new LinkedHashMap<String,Object>(); clear.put("maxSelection",null);
        assertThat(service.updateAddonGroup(1L,1L,clear)).containsEntry("minSelection",0);
        assertThat(jdbc.queryForObject("select max_selection from menu.lkp_addon_groups where addon_group_id=1",Integer.class)).isNull();
        assertThat(service.updateAddonGroup(1L,1L,Map.of("maxSelection",2))).containsEntry("maxSelection",2);
    }
    @Test void anotherCommerceCannotEditTheSameIds() {
        assertThatThrownBy(() -> service.updateAddon(2L,1L,1L,Map.of("price",0)))
            .isInstanceOf(com.kaffe.common.exception.ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("select price from menu.lkp_addons where addon_id=1",Integer.class)).isEqualTo(1400);
    }
}
