package com.kaffe.menuadmin.service.ai;

import com.kaffe.common.exception.BadRequestException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Bounded, finalized mono PCM. Never trust MIME or a client-supplied duration. */
final class MenuAudioInput {
    private MenuAudioInput() { }

    static void validate(byte[] bytes) {
        if (bytes == null || bytes.length < 8_044 || bytes.length > 2_880_044) {
            throw new BadRequestException("Graba entre un segundo y 90 segundos para crear el menú");
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (!new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                || !new String(bytes, 8, 8, StandardCharsets.US_ASCII).equals("WAVEfmt ")
                || !new String(bytes, 36, 4, StandardCharsets.US_ASCII).equals("data")
                || data.getInt(4) != bytes.length - 8 || data.getInt(16) != 16
                || data.getShort(20) != 1 || data.getShort(22) != 1
                || data.getInt(24) != 16_000 || data.getInt(28) != 32_000
                || data.getShort(32) != 2 || data.getShort(34) != 16
                || data.getInt(40) != bytes.length - 44 || (bytes.length - 44) % 2 != 0) {
            throw new BadRequestException("El audio no está completo. Vuelve a dictar el menú.");
        }
    }
}
