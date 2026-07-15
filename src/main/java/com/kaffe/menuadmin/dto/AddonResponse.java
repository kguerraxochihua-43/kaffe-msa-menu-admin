package com.kaffe.menuadmin.dto;

public record AddonResponse(
        Long addonId,
        Long cafeteriaId,
        Long addonGroupId,
        String name,
        String description,
        int price,
        boolean isDefault,
        int sortOrder,
        boolean active
) {
}
