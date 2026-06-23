package com.kaffe.menuadmin.controller;

import com.kaffe.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MenuAdminHealthController {

    @GetMapping("/health")
    public String health() {
        return "kaffe-msa-menu-admin OK";
    }

    @GetMapping("/health/json")
    public ApiResponse<Map<String, String>> healthJson() {
        return ApiResponse.ok("Menu admin service is healthy", Map.of("service", "kaffe-msa-menu-admin", "status", "UP"));
    }
}
