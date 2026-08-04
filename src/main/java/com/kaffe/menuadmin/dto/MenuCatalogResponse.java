package com.kaffe.menuadmin.dto;

import java.util.List;
import java.util.Map;

public record MenuCatalogResponse(
        Long cafeteriaId,
        Long locationId,
        Map<String, Object> summary,
        List<Map<String, Object>> categories,
        List<Map<String, Object>> products,
        List<Map<String, Object>> addonGroups,
        List<Map<String, Object>> addons,
        List<Map<String, Object>> productAddonGroups
) {
}
