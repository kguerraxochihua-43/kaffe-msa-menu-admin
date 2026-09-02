package com.kaffe.menuadmin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

public final class MenuScheduleDtos {
    private MenuScheduleDtos() {
    }

    public record SaveWindowRequest(
            @NotNull @Positive Long menuId,
            @NotEmpty @Size(max = 7) Set<@Min(1) @Max(7) Integer> daysOfWeek,
            @NotNull LocalTime startsAt,
            @NotNull LocalTime endsAt,
            LocalDate validFrom,
            LocalDate validTo,
            Boolean override,
            Boolean active,
            @Min(1) Integer expectedVersion
    ) {
    }

    public record MenuOption(
            Long menuId,
            String name,
            String description,
            boolean global,
            boolean active,
            int sortOrder,
            List<Long> categoryIds
    ) {
    }

    public record Window(
            Long menuScheduleWindowId,
            Long menuId,
            String menuName,
            int dayOfWeek,
            LocalTime startsAt,
            LocalTime endsAt,
            LocalDate validFrom,
            LocalDate validTo,
            boolean override,
            boolean active,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record Catalog(
            Long cafeteriaId,
            Long locationId,
            String locationName,
            String timeZone,
            List<MenuOption> menus,
            List<Window> windows
    ) {
    }
}
