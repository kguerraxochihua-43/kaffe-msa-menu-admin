package com.kaffe.menuadmin.dto;

public record ProductAddonGroupResponse(
        Long cafeteriaId,
        Long productId,
        Long addonGroupId,
        int sortOrder,
        boolean active
) {
}
