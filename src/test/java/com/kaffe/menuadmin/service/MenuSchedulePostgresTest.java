package com.kaffe.menuadmin.service;

import com.kaffe.common.exception.ConflictException;
import com.kaffe.common.exception.ForbiddenException;
import com.kaffe.common.exception.ResourceNotFoundException;
import com.kaffe.common.security.CurrentUserProvider;
import com.kaffe.menuadmin.dto.MenuScheduleDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real SQL, including an empty branch; string assertions cannot detect parser errors. */
@EnabledIfEnvironmentVariable(named = "KAFFE_MENU_TEST_DB", matches = "jdbc:postgresql://localhost:[0-9]+/kaffe_menu_test_[a-z0-9_]+")
class MenuSchedulePostgresTest {
    private JdbcTemplate jdbc;
    private MenuScheduleService service;
    private boolean testDatabaseVerified;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("KAFFE_MENU_TEST_DB"), "postgres",
                System.getenv().getOrDefault("KAFFE_MENU_TEST_PASSWORD", "")));
        assertThat(jdbc.queryForObject("select current_database()", String.class)).startsWith("kaffe_menu_test_");
        testDatabaseVerified = true;
        jdbc.execute("""
                create schema auth; create schema catalog; create schema core; create schema menu;
                create table auth.tenant_user_role_assignments(user_id bigint, tenant_id bigint,
                  tenant_role_id bigint, location_id bigint, status_id int);
                create table auth.tenant_roles(tenant_role_id bigint, tenant_id bigint, status_id int);
                create table auth.tenant_role_policies(tenant_role_id bigint, policy_id bigint, enabled boolean);
                create table catalog.lkp_policies(policy_id bigint, code text, status_id int);
                create table core.locations(location_id bigint primary key, tenant_id bigint, name text,
                  time_zone text, status_id int);
                insert into core.locations values
                  (7,1,'Jardín','America/Mexico_City',1), (8,1,'Patio','America/Cancun',1), (9,2,'Otro comercio','UTC',1);
                insert into auth.tenant_user_role_assignments values(1,1,1,7,1);
                insert into auth.tenant_roles values(1,1,1);
                insert into auth.tenant_role_policies values(1,1,true),(1,2,true);
                insert into catalog.lkp_policies values(1,'menu:read',1),(2,'menu:write',1);
                create table menu.menus(menu_id bigint primary key, tenant_id bigint, name text,
                  description text, is_global boolean, is_active boolean default true, sort_order int default 0,
                  deleted_at timestamptz);
                create table menu.menu_categories(tenant_id bigint, menu_id bigint, category_id bigint,
                  sort_order int default 0, is_active boolean default true, deleted_at timestamptz);
                create table menu.menu_locations(tenant_id bigint, menu_id bigint, location_id bigint,
                  is_active boolean default true, deleted_at timestamptz);
                create table menu.menu_schedule_windows(menu_schedule_window_id bigint generated always as identity primary key,
                  tenant_id bigint, location_id bigint, menu_id bigint, day_of_week int,
                  starts_at time, ends_at time, valid_from date, valid_to date,
                  is_override boolean default false, is_active boolean default true, version int default 1,
                  created_at timestamptz default now(), updated_at timestamptz default now(), deleted_at timestamptz);
                insert into menu.menus(menu_id,tenant_id,name,is_global) values
                  (10,1,'General',true),(11,1,'Tarde',false),(12,1,'Otro local',false),(20,2,'Privado',true);
                insert into menu.menu_locations(tenant_id,menu_id,location_id) values(1,11,7),(1,12,8);
                insert into menu.menu_categories(tenant_id,menu_id,category_id,sort_order,is_active) values
                  (1,10,100,2,true),(1,10,101,1,true),(1,10,102,3,false);
                """);
        var user = mock(CurrentUserProvider.class);
        when(user.requireUserId()).thenReturn(1L);
        service = new MenuScheduleService(jdbc, user);
    }

    @AfterEach
    void cleanup() {
        if (jdbc != null && testDatabaseVerified) {
            jdbc.execute("drop schema if exists menu cascade; drop schema if exists core cascade; "
                    + "drop schema if exists auth cascade; drop schema if exists catalog cascade");
        }
    }

    @Test
    void opensBranchWithoutSchedulesAndKeepsActiveCategories() {
        var catalog = service.catalog(1L, 7L);
        assertThat(catalog.locationName()).isEqualTo("Jardín");
        assertThat(catalog.timeZone()).isEqualTo("America/Mexico_City");
        assertThat(catalog.menus()).extracting(MenuScheduleDtos.MenuOption::menuId).containsExactly(10L, 11L);
        assertThat(catalog.menus().getFirst().categoryIds()).containsExactlyInAnyOrder(101L, 100L);
        assertThat(catalog.menus().getLast().categoryIds()).isEmpty();
        assertThat(catalog.windows()).isEmpty();
    }

    @Test
    void returnsOnlyThisBranchWindowsExcludingDeletedRows() {
        jdbc.update("""
                insert into menu.menu_schedule_windows(tenant_id,location_id,menu_id,day_of_week,starts_at,ends_at,deleted_at)
                values (1,7,10,2,'08:00','12:00',null), (1,7,11,1,'15:00','19:00',null),
                       (1,8,12,1,'08:00','12:00',null), (2,9,20,1,'08:00','12:00',null),
                       (1,7,10,3,'08:00','12:00',now())
                """);
        var windows = service.catalog(1L, 7L).windows();
        assertThat(windows).extracting(MenuScheduleDtos.Window::menuName).containsExactly("Tarde", "General");
        assertThat(windows.getFirst().startsAt()).isEqualTo(LocalTime.of(15, 0));
        assertThat(windows.getFirst().createdAt()).isNotNull();
    }

    @Test
    void readPermissionDoesNotPermitChangingSchedules() {
        jdbc.update("update auth.tenant_role_policies set enabled = false where policy_id = 2");
        assertThat(service.catalog(1L, 7L).menus()).hasSize(2);
        assertThatThrownBy(() -> service.create(1L, 7L, request(null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void deniesUnassignedBranchAndAnotherCommerce() {
        assertThatThrownBy(() -> service.catalog(1L, 8L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.catalog(2L, 9L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rechecksRevokedPermissionOnEveryRead() {
        service.catalog(1L, 7L);
        jdbc.update("update auth.tenant_role_policies set enabled = false");
        assertThatThrownBy(() -> service.catalog(1L, 7L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void createEditAndSoftDeleteReturnReadableCatalogsAndRejectOldVersions() {
        var created = service.create(1L, 7L, request(null)).windows().getFirst();
        assertThat(created.version()).isEqualTo(1);
        var edited = service.update(1L, 7L, created.menuScheduleWindowId(), request(1)).windows().getFirst();
        assertThat(edited.version()).isEqualTo(2);
        assertThatThrownBy(() -> service.delete(1L, 7L, edited.menuScheduleWindowId(), 1))
                .isInstanceOf(ConflictException.class);
        assertThat(service.delete(1L, 7L, edited.menuScheduleWindowId(), 2).windows()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from menu.menu_schedule_windows where deleted_at is not null", Integer.class))
                .isEqualTo(1);
    }

    private MenuScheduleDtos.SaveWindowRequest request(Integer version) {
        return new MenuScheduleDtos.SaveWindowRequest(10L, Set.of(1), LocalTime.of(8, 0), LocalTime.of(12, 0),
                null, null, false, true, version);
    }
}
