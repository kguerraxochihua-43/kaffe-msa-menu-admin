package com.kaffe.menuadmin.controller;

import com.kaffe.common.web.ApiResponse;
import com.kaffe.menuadmin.dto.MenuScheduleDtos;
import com.kaffe.menuadmin.service.MenuScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menu-admin/cafeterias/{cafeteriaId}/locations/{locationId}/menu-schedules")
@RequiredArgsConstructor
public class MenuScheduleController {
    private final MenuScheduleService menuScheduleService;

    @GetMapping
    public ApiResponse<MenuScheduleDtos.Catalog> catalog(
            @PathVariable Long cafeteriaId,
            @PathVariable Long locationId
    ) {
        return ApiResponse.ok(
                "Menu schedules found",
                menuScheduleService.catalog(cafeteriaId, locationId)
        );
    }

    @PostMapping
    public ApiResponse<MenuScheduleDtos.Catalog> create(
            @PathVariable Long cafeteriaId,
            @PathVariable Long locationId,
            @Valid @RequestBody MenuScheduleDtos.SaveWindowRequest request
    ) {
        return ApiResponse.ok(
                "Menu schedule created successfully",
                menuScheduleService.create(cafeteriaId, locationId, request)
        );
    }

    @PutMapping("/{windowId}")
    public ApiResponse<MenuScheduleDtos.Catalog> update(
            @PathVariable Long cafeteriaId,
            @PathVariable Long locationId,
            @PathVariable Long windowId,
            @Valid @RequestBody MenuScheduleDtos.SaveWindowRequest request
    ) {
        return ApiResponse.ok(
                "Menu schedule updated successfully",
                menuScheduleService.update(cafeteriaId, locationId, windowId, request)
        );
    }

    @DeleteMapping("/{windowId}")
    public ApiResponse<MenuScheduleDtos.Catalog> delete(
            @PathVariable Long cafeteriaId,
            @PathVariable Long locationId,
            @PathVariable Long windowId,
            @RequestParam @jakarta.validation.constraints.Min(1) Integer expectedVersion
    ) {
        return ApiResponse.ok(
                "Menu schedule deleted successfully",
                menuScheduleService.delete(cafeteriaId, locationId, windowId, expectedVersion)
        );
    }
}
