package com.kaffe.menuadmin.service;

import com.kaffe.common.exception.BadRequestException;
import com.kaffe.common.exception.ConflictException;
import com.kaffe.common.exception.ForbiddenException;
import com.kaffe.common.exception.ResourceNotFoundException;
import com.kaffe.common.security.CurrentUserProvider;
import com.kaffe.common.sql.SqlSchemas;
import com.kaffe.menuadmin.dto.MenuScheduleDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MenuScheduleService {
    private static final String POLICY_MENU_READ = "menu:read";
    private static final String POLICY_MENU_WRITE = "menu:write";

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserProvider currentUserProvider;

    @Value("${kaffe.auth.schema:kaffe_auth}")
    private String authSchema = "auth";

    @Transactional(readOnly = true)
    public MenuScheduleDtos.Catalog catalog(Long cafeteriaId, Long locationId) {
        requireLocationAccess(cafeteriaId, locationId, false);
        return loadCatalog(cafeteriaId, locationId);
    }

    @Transactional
    public MenuScheduleDtos.Catalog create(
            Long cafeteriaId,
            Long locationId,
            MenuScheduleDtos.SaveWindowRequest request
    ) {
        requireLocationAccess(cafeteriaId, locationId, true);
        validateRequest(cafeteriaId, locationId, null, request, false);
        List<Integer> days = request.daysOfWeek().stream().sorted().toList();
        try {
            for (Integer day : days) {
                jdbcTemplate.update("""
                                insert into menu.menu_schedule_windows (
                                    tenant_id, location_id, menu_id, day_of_week,
                                    starts_at, ends_at, valid_from, valid_to,
                                    is_override, is_active
                                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        cafeteriaId,
                        locationId,
                        request.menuId(),
                        day,
                        request.startsAt(),
                        request.endsAt(),
                        request.validFrom(),
                        request.validTo(),
                        Boolean.TRUE.equals(request.override()),
                        request.active() == null || request.active());
            }
        } catch (DataIntegrityViolationException ex) {
            throw scheduleConflict(ex);
        }
        return loadCatalog(cafeteriaId, locationId);
    }

    @Transactional
    public MenuScheduleDtos.Catalog update(
            Long cafeteriaId,
            Long locationId,
            Long windowId,
            MenuScheduleDtos.SaveWindowRequest request
    ) {
        requireLocationAccess(cafeteriaId, locationId, true);
        if (request.expectedVersion() == null) {
            throw new BadRequestException("expectedVersion is required when updating a menu schedule");
        }
        if (request.daysOfWeek().size() != 1) {
            throw new BadRequestException("Edit one scheduled day at a time");
        }
        validateRequest(cafeteriaId, locationId, windowId, request, true);
        Integer day = request.daysOfWeek().iterator().next();
        try {
            int updated = jdbcTemplate.update("""
                            update menu.menu_schedule_windows
                               set menu_id = ?,
                                   day_of_week = ?,
                                   starts_at = ?,
                                   ends_at = ?,
                                   valid_from = ?,
                                   valid_to = ?,
                                   is_override = ?,
                                   is_active = ?,
                                   version = version + 1
                             where tenant_id = ?
                               and location_id = ?
                               and menu_schedule_window_id = ?
                               and version = ?
                               and deleted_at is null
                            """,
                    request.menuId(),
                    day,
                    request.startsAt(),
                    request.endsAt(),
                    request.validFrom(),
                    request.validTo(),
                    Boolean.TRUE.equals(request.override()),
                    request.active() == null || request.active(),
                    cafeteriaId,
                    locationId,
                    windowId,
                    request.expectedVersion());
            if (updated == 0) {
                throw versionConflict(cafeteriaId, locationId, windowId);
            }
        } catch (DataIntegrityViolationException ex) {
            throw scheduleConflict(ex);
        }
        return loadCatalog(cafeteriaId, locationId);
    }

    @Transactional
    public MenuScheduleDtos.Catalog delete(
            Long cafeteriaId,
            Long locationId,
            Long windowId,
            Integer expectedVersion
    ) {
        requireLocationAccess(cafeteriaId, locationId, true);
        int updated = jdbcTemplate.update("""
                        update menu.menu_schedule_windows
                           set is_active = false,
                               deleted_at = now(),
                               version = version + 1
                         where tenant_id = ?
                           and location_id = ?
                           and menu_schedule_window_id = ?
                           and version = ?
                           and deleted_at is null
                        """,
                cafeteriaId,
                locationId,
                windowId,
                expectedVersion);
        if (updated == 0) {
            throw versionConflict(cafeteriaId, locationId, windowId);
        }
        return loadCatalog(cafeteriaId, locationId);
    }

    private void validateRequest(
            Long cafeteriaId,
            Long locationId,
            Long windowId,
            MenuScheduleDtos.SaveWindowRequest request,
            boolean update
    ) {
        if (!request.startsAt().isBefore(request.endsAt())) {
            throw new BadRequestException("The start time must be before the end time");
        }
        if (request.validFrom() != null
                && request.validTo() != null
                && request.validFrom().isAfter(request.validTo())) {
            throw new BadRequestException("The validity start date must be before the end date");
        }
        if (Boolean.TRUE.equals(request.override())
                && (request.validFrom() == null || request.validTo() == null)) {
            throw new BadRequestException("A menu exception requires a start and end date");
        }
        if (update) {
            ensureWindow(cafeteriaId, locationId, windowId);
        }
        Boolean eligible = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1
                              from menu.menus candidate
                             where candidate.menu_id = ?
                               and candidate.tenant_id = ?
                               and candidate.is_active = true
                               and candidate.deleted_at is null
                               and (
                                   candidate.is_global = true
                                   or exists (
                                       select 1
                                         from menu.menu_locations assignment
                                        where assignment.menu_id = candidate.menu_id
                                          and assignment.tenant_id = candidate.tenant_id
                                          and assignment.location_id = ?
                                          and assignment.is_active = true
                                          and assignment.deleted_at is null
                                   )
                               )
                        )
                        """, Boolean.class, request.menuId(), cafeteriaId, locationId);
        if (!Boolean.TRUE.equals(eligible)) {
            throw new BadRequestException("The menu must be active and available at this branch");
        }

        for (Integer day : request.daysOfWeek()) {
            Boolean overlap = jdbcTemplate.queryForObject("""
                            select exists(
                                select 1
                                  from menu.menu_schedule_windows existing
                                 where existing.tenant_id = ?
                                   and existing.location_id = ?
                                   and existing.day_of_week = ?
                                   and existing.is_override = ?
                                   and existing.is_active = true
                                   and existing.deleted_at is null
                                   and existing.menu_schedule_window_id <> coalesce(?::bigint, -1)
                                   and existing.starts_at < ?
                                   and ? < existing.ends_at
                                   and coalesce(existing.valid_from, '-infinity'::date)
                                       <= coalesce(?, 'infinity'::date)
                                   and coalesce(?, '-infinity'::date)
                                       <= coalesce(existing.valid_to, 'infinity'::date)
                            )
                            """,
                    Boolean.class,
                    cafeteriaId,
                    locationId,
                    day,
                    Boolean.TRUE.equals(request.override()),
                    windowId,
                    request.endsAt(),
                    request.startsAt(),
                    request.validTo(),
                    request.validFrom());
            if (Boolean.TRUE.equals(overlap)) {
                throw new ConflictException("Another menu already covers part of that schedule");
            }
        }
    }

    private MenuScheduleDtos.Catalog loadCatalog(Long cafeteriaId, Long locationId) {
        Location location;
        try {
            location = jdbcTemplate.queryForObject("""
                            select name, time_zone
                              from core.locations
                             where tenant_id = ?
                               and location_id = ?
                               and status_id <> 4
                            """,
                    (rs, rowNum) -> new Location(
                            rs.getString("name"),
                            rs.getString("time_zone")
                    ),
                    cafeteriaId,
                    locationId);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResourceNotFoundException("Location was not found for this cafeteria");
        }

        List<MenuScheduleDtos.MenuOption> menus = jdbcTemplate.query("""
                        select candidate.menu_id,
                               candidate.name,
                               candidate.description,
                               candidate.is_global,
                               candidate.is_active,
                               candidate.sort_order,
                               coalesce(array_agg(mc.category_id order by mc.sort_order)
                                   filter (where mc.category_id is not null), '{}') as category_ids
                          from menu.menus candidate
                          left join menu.menu_categories mc
                            on mc.tenant_id = candidate.tenant_id
                           and mc.menu_id = candidate.menu_id
                           and mc.is_active = true
                           and mc.deleted_at is null
                         where candidate.tenant_id = ?
                           and candidate.deleted_at is null
                           and (
                               candidate.is_global = true
                               or exists (
                                   select 1
                                     from menu.menu_locations assignment
                                    where assignment.tenant_id = candidate.tenant_id
                                      and assignment.menu_id = candidate.menu_id
                                      and assignment.location_id = ?
                                      and assignment.is_active = true
                                      and assignment.deleted_at is null
                               )
                           )
                         group by candidate.menu_id
                         order by candidate.sort_order, candidate.name
                        """,
                (rs, rowNum) -> new MenuScheduleDtos.MenuOption(
                        rs.getLong("menu_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBoolean("is_global"),
                        rs.getBoolean("is_active"),
                        rs.getInt("sort_order"),
                        longArray(rs.getArray("category_ids"))
                ),
                cafeteriaId,
                locationId);

        List<MenuScheduleDtos.Window> windows = jdbcTemplate.query("""
                        select window.menu_schedule_window_id,
                               window.menu_id,
                               candidate.name as menu_name,
                               window.day_of_week,
                               window.starts_at,
                               window.ends_at,
                               window.valid_from,
                               window.valid_to,
                               window.is_override,
                               window.is_active,
                               window.version,
                               window.created_at,
                               window.updated_at
                          from menu.menu_schedule_windows window
                          join menu.menus candidate
                            on candidate.tenant_id = window.tenant_id
                           and candidate.menu_id = window.menu_id
                         where window.tenant_id = ?
                           and window.location_id = ?
                           and window.deleted_at is null
                         order by window.day_of_week, window.starts_at, candidate.name
                        """,
                (rs, rowNum) -> new MenuScheduleDtos.Window(
                        rs.getLong("menu_schedule_window_id"),
                        rs.getLong("menu_id"),
                        rs.getString("menu_name"),
                        rs.getInt("day_of_week"),
                        rs.getObject("starts_at", LocalTime.class),
                        rs.getObject("ends_at", LocalTime.class),
                        rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class),
                        rs.getBoolean("is_override"),
                        rs.getBoolean("is_active"),
                        rs.getInt("version"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ),
                cafeteriaId,
                locationId);

        return new MenuScheduleDtos.Catalog(
                cafeteriaId,
                locationId,
                location.name(),
                location.timeZone(),
                menus,
                windows
        );
    }

    private RuntimeException versionConflict(Long cafeteriaId, Long locationId, Long windowId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from menu.menu_schedule_windows
                             where tenant_id = ?
                               and location_id = ?
                               and menu_schedule_window_id = ?
                               and deleted_at is null
                        )
                        """, Boolean.class, cafeteriaId, locationId, windowId);
        if (!Boolean.TRUE.equals(exists)) {
            return new ResourceNotFoundException("Menu schedule was not found");
        }
        return new ConflictException("This schedule changed on another device; refresh before trying again");
    }

    private void ensureWindow(Long cafeteriaId, Long locationId, Long windowId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from menu.menu_schedule_windows
                             where tenant_id = ?
                               and location_id = ?
                               and menu_schedule_window_id = ?
                               and deleted_at is null
                        )
                        """, Boolean.class, cafeteriaId, locationId, windowId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResourceNotFoundException("Menu schedule was not found");
        }
    }

    private RuntimeException scheduleConflict(DataIntegrityViolationException ignored) {
        return new ConflictException("The schedule overlaps another menu or is no longer available at this branch");
    }

    private List<Long> longArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object raw = sqlArray.getArray();
        List<Long> result = new ArrayList<>();
        if (raw instanceof Object[] values) {
            for (Object value : values) {
                if (value instanceof Number number) {
                    result.add(number.longValue());
                }
            }
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private void requireLocationAccess(Long cafeteriaId, Long locationId, boolean writeRequired) {
        Long userId = currentUserProvider.requireUserId();
        Boolean allowed = jdbcTemplate.queryForObject(authSql("""
                        select exists(
                            select 1
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
                              join core.locations location
                                on location.tenant_id = ura.tenant_id
                               and location.location_id = ?
                               and location.status_id <> 4
                             where ura.user_id = ?
                               and ura.tenant_id = ?
                               and ura.status_id = 1
                               and (ura.location_id is null or ura.location_id = location.location_id)
                               and (
                                   (? = true and p.code = ?)
                                   or (? = false and p.code in (?, ?))
                               )
                        )
                        """),
                Boolean.class,
                locationId,
                userId,
                cafeteriaId,
                writeRequired,
                POLICY_MENU_WRITE,
                writeRequired,
                POLICY_MENU_READ,
                POLICY_MENU_WRITE);
        if (Boolean.TRUE.equals(allowed)) {
            return;
        }

        Boolean assigned = jdbcTemplate.queryForObject(authSql("""
                        select exists(
                            select 1
                              from auth.tenant_user_role_assignments
                             where user_id = ?
                               and tenant_id = ?
                               and status_id = 1
                               and (location_id is null or location_id = ?)
                        )
                        """), Boolean.class, userId, cafeteriaId, locationId);
        if (Boolean.TRUE.equals(assigned)) {
            throw new ForbiddenException(writeRequired
                    ? "User cannot manage menu schedules in this scope"
                    : "User cannot read menu schedules in this scope");
        }
        throw new ResourceNotFoundException("Location was not found for this user");
    }

    private String authSql(String sql) {
        return sql.replace("auth.", SqlSchemas.schema(authSchema) + ".");
    }

    private record Location(String name, String timeZone) {
    }
}
