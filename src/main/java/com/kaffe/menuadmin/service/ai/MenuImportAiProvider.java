package com.kaffe.menuadmin.service.ai;

import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftPayload;

public interface MenuImportAiProvider {

    boolean isReady();

    String providerCode();

    String modelCode();

    DraftPayload extract(MenuImageInput image, String safetyIdentifier);

    default DraftPayload extract(MenuImageInput image, String text, String safetyIdentifier) {
        return extract(image, safetyIdentifier);
    }

    default String transcribe(byte[] audio) {
        throw new MenuImportAiException("El dictado no está disponible");
    }
}
