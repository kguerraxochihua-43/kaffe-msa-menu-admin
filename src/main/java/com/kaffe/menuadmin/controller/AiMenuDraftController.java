package com.kaffe.menuadmin.controller;

import com.kaffe.common.exception.BadRequestException;
import com.kaffe.common.web.ApiResponse;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftResponse;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.PublishRequest;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.UpdateRequest;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.CreateTextRequest;
import com.kaffe.menuadmin.service.ai.AiMenuDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/menu-admin/cafeterias/{cafeteriaId}/ai-menu-drafts")
@RequiredArgsConstructor
public class AiMenuDraftController {

    private final AiMenuDraftService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DraftResponse> create(
            @PathVariable Long cafeteriaId,
            @RequestParam String idempotencyKey,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            @RequestParam(required = false) String text
    ) {
        try {
            return ApiResponse.ok(
                    "Borrador de menú digitalizado",
                    service.create(cafeteriaId, idempotencyKey, image == null ? null : image.getBytes(),
                            audio == null ? null : audio.getBytes(), text)
            );
        } catch (IOException ex) {
            throw new BadRequestException("No pudimos leer el archivo enviado");
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<DraftResponse> createText(@PathVariable Long cafeteriaId, @RequestBody CreateTextRequest request) {
        if (request == null) throw new BadRequestException("Escribe el contenido del menú");
        return ApiResponse.ok("Borrador de menú preparado", service.create(cafeteriaId,
                request.idempotencyKey(), null, null, request.text()));
    }

    @GetMapping
    public ApiResponse<List<DraftResponse>> list(
            @PathVariable Long cafeteriaId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok("Borradores de menú", service.list(cafeteriaId, limit));
    }

    @GetMapping("/{draftId}")
    public ApiResponse<DraftResponse> get(
            @PathVariable Long cafeteriaId,
            @PathVariable UUID draftId
    ) {
        return ApiResponse.ok("Borrador de menú", service.get(cafeteriaId, draftId));
    }

    @PatchMapping("/{draftId}")
    public ApiResponse<DraftResponse> update(
            @PathVariable Long cafeteriaId,
            @PathVariable UUID draftId,
            @RequestBody UpdateRequest request
    ) {
        return ApiResponse.ok(
                "Borrador actualizado",
                service.update(cafeteriaId, draftId, request)
        );
    }

    @PostMapping("/{draftId}/publish")
    public ApiResponse<DraftResponse> publish(
            @PathVariable Long cafeteriaId,
            @PathVariable UUID draftId,
            @RequestBody PublishRequest request
    ) {
        return ApiResponse.ok(
                request.createSeparateMenu() ? "Menú guardado e inactivo" : "Menú publicado",
                service.publish(cafeteriaId, draftId, request)
        );
    }

    @DeleteMapping("/{draftId}")
    public ApiResponse<DraftResponse> discard(
            @PathVariable Long cafeteriaId,
            @PathVariable UUID draftId,
            @RequestParam int expectedVersion
    ) {
        return ApiResponse.ok(
                "Borrador descartado",
                service.discard(cafeteriaId, draftId, expectedVersion)
        );
    }
}
