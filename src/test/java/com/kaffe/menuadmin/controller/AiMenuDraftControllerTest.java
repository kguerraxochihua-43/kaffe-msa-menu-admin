package com.kaffe.menuadmin.controller;

import com.kaffe.menuadmin.service.ai.AiMenuDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiMenuDraftControllerTest {
    @Test void acceptsTextWithoutAnImagePart() throws Exception {
        var service = mock(AiMenuDraftService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AiMenuDraftController(service)).build();
        mvc.perform(post("/api/menu-admin/cafeterias/7/ai-menu-drafts")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\":\"test-idempotency\",\"text\":\"Cafés: latte 60 pesos\"}"))
                .andExpect(status().isOk());
        verify(service).create(7L, "test-idempotency", null, null, "Cafés: latte 60 pesos");
    }

    @Test void combinedCaptureAndAudioOnlyReachOneServiceOperation() throws Exception {
        var service = mock(AiMenuDraftService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AiMenuDraftController(service)).build();
        byte[] image = new byte[] {1, 2, 3};
        byte[] audio = new byte[] {4, 5, 6}; // Controller transport only; service validates real WAV bytes.
        mvc.perform(multipart("/api/menu-admin/cafeterias/7/ai-menu-drafts")
                        .file(new MockMultipartFile("image", "menu.jpg", "image/jpeg", image))
                        .file(new MockMultipartFile("audio", "menu.wav", "audio/wav", audio))
                        .param("idempotencyKey", "mixed-input").param("text", "Latte ahora 65 pesos"))
                .andExpect(status().isOk());
        verify(service).create(7L, "mixed-input", image, audio, "Latte ahora 65 pesos");
        mvc.perform(multipart("/api/menu-admin/cafeterias/7/ai-menu-drafts")
                        .file(new MockMultipartFile("audio", "menu.wav", "audio/wav", audio))
                        .param("idempotencyKey", "audio-only"))
                .andExpect(status().isOk());
        verify(service).create(7L, "audio-only", null, audio, null);
        verifyNoMoreInteractions(service);
    }
}
