package com.kaffe.menuadmin.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaffe.menuadmin.dto.AiMenuDraftDtos.DraftPayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiMenuImportProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsAnEphemeralImageInputAndParsesStrictStructuredOutput() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            captured.set(objectMapper.readTree(exchange.getRequestBody()));
            String payload = objectMapper.writeValueAsString(Map.of(
                    "title", "Menú",
                    "currencyCode", "MXN",
                    "categories", List.of(Map.of(
                            "name", "Cafés",
                            "description", "Calientes",
                            "sortOrder", 0,
                            "products", List.of(Map.of(
                                    "name", "Espresso",
                                    "description", "Doble",
                                    "basePriceMinor", 4800,
                                    "priceText", "$48",
                                    "sortOrder", 0,
                                    "needsReview", false,
                                    "reviewReasons", List.of()
                            ))
                    )),
                    "warnings", List.of()
            ));
            String response = objectMapper.writeValueAsString(Map.of(
                    "status", "completed",
                    "output", List.of(Map.of(
                            "type", "message",
                            "content", List.of(Map.of(
                                    "type", "output_text",
                                    "text", payload
                            ))
                    ))
            ));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiMenuImportProvider provider = new OpenAiMenuImportProvider(
                    objectMapper,
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:%d/v1/responses".formatted(server.getAddress().getPort())),
                    true,
                    "server-secret",
                    "gpt-5.4-mini",
                    Duration.ofSeconds(5),
                    4_000,
                    true
            );
            byte[] image = new byte[1024];
            image[0] = (byte) 0xff;
            image[1] = (byte) 0xd8;
            image[2] = (byte) 0xff;
            DraftPayload result = provider.extract(
                    new MenuImageInput(image, "image/jpeg"),
                    "anonymous-hash"
            );

            assertThat(result.categories()).hasSize(1);
            JsonNode request = captured.get();
            assertThat(request.path("store").asBoolean()).isFalse();
            assertThat(request.path("tool_choice").asText()).isEqualTo("none");
            assertThat(request.path("safety_identifier").asText()).isEqualTo("anonymous-hash");
            assertThat(request.at("/text/format/type").asText()).isEqualTo("json_schema");
            assertThat(request.at("/text/format/strict").asBoolean()).isTrue();
            assertThat(request.at("/input/0/content/1/type").asText()).isEqualTo("input_image");
            assertThat(request.at("/input/0/content/1/image_url").asText())
                    .startsWith("data:image/jpeg;base64,");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsProviderEndpointsOutsideTheOfficialResponsesApi() {
        assertThatThrownBy(() -> new OpenAiMenuImportProvider(
                objectMapper,
                HttpClient.newHttpClient(),
                URI.create("https://api.openai.com.attacker.test/v1/responses"),
                true,
                "secret",
                "gpt-5.4-mini",
                Duration.ofSeconds(5),
                4_000,
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
