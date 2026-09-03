package com.kaffe.menuadmin.service.ai;

import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftPayload;

public interface MenuImportAiProvider {

    boolean isReady();

    String providerCode();

    String modelCode();

    DraftPayload extract(MenuImageInput image, String safetyIdentifier);
}
