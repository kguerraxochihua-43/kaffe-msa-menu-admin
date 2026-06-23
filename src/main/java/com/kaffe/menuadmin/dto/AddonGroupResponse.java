package com.kaffe.menuadmin.dto;

public record AddonGroupResponse(
        Long addonGroupId,
        Long cafeteriaId,
        String name,
        String description,
        boolean required,
        int minSelection,
        Integer maxSelection,
        int sortOrder,
        boolean active
) {
}
