package com.kaffe.menuadmin.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Prices are minor units, not strings or rounded decimal JSON numbers. */
public final class MenuMinorAmountDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Integer.class, "El precio debe ser un número entero en centavos");
        }
        return parser.getIntValue();
    }
}
