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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiMenuImportProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String ASSISTED_DRAFT = """
            {"title":"Menú de la tarde","currencyCode":"MXN","warnings":[],"categories":[
              {"name":"Cafés","description":null,"sortOrder":0,"products":[
                {"name":"Latte","description":null,"basePriceMinor":null,"priceText":null,
                 "sortOrder":0,"needsReview":true,"reviewReasons":["Confirma el precio"],
                 "optionGroups":[{"name":"Tamaño","required":true,"minSelection":1,"maxSelection":1,
                   "options":[{"name":"Chico","priceMinor":0,"isDefault":false},
                              {"name":"Grande","priceMinor":1500,"isDefault":false}]}]}]}]}
            """;

    @Test
    void textAndMixedInputsKeepNullableMoneyAndIncrementalOptions() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            captured.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] bytes = response(ASSISTED_DRAFT);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var provider = provider(server, Duration.ofSeconds(3));
            var draft = provider.extract(null, "Latte chico y grande +15", "opaque-actor");
            var product = draft.categories().getFirst().products().getFirst();
            assertThat(product.basePriceMinor()).isNull();
            assertThat(product.optionGroups().getFirst().options().getLast().priceMinor()).isEqualTo(1500);
            assertThat(captured.get().at("/input/0/content").size()).isEqualTo(1);
            assertThat(captured.get().at("/input/0/content/0/text").asText()).contains("Latte");
            JsonNode schema = captured.get().at("/text/format/schema/properties/categories/items/properties/products/items");
            assertThat(schema.at("/properties/basePriceMinor/type").toString()).isEqualTo("[\"integer\",\"null\"]");
            assertThat(schema.at("/properties/optionGroups/items/additionalProperties").asBoolean(true)).isFalse();
            provider.extract(new MenuImageInput(new byte[1024], "image/jpeg"), "Corrige latte: 65 pesos", "opaque-actor");
            assertThat(captured.get().at("/input/0/content").size()).isEqualTo(2);
            assertThat(captured.get().at("/input/0/content/0/text").asText()).contains("65 pesos");
        } finally { server.stop(0); }
    }

    @Test
    void voiceUsesBoundedWavAndSmallTranscriptionModel() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] bytes = "{\"text\":\"Cafés: latte sesenta pesos\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var provider = provider(server, Duration.ofSeconds(3));
            assertThat(provider.transcribe(wave())).isEqualTo("Cafés: latte sesenta pesos");
            assertThat(contentType.get()).startsWith("multipart/form-data; boundary=kaffe-menu-");
            assertThat(captured.get()).contains("gpt-4o-mini-transcribe-2025-12-15", "filename=\"menu.wav\"", "audio/wav", "RIFF");
            assertThatThrownBy(() -> provider.transcribe(new byte[8044]))
                    .isInstanceOf(com.kaffe.common.exception.BadRequestException.class);
            byte[] truncated = java.util.Arrays.copyOf(wave(), 8000);
            assertThatThrownBy(() -> MenuAudioInput.validate(truncated))
                    .isInstanceOf(com.kaffe.common.exception.BadRequestException.class);
        } finally { server.stop(0); }
    }

    @Test
    void invalidMoneyAndUnknownFieldsCannotBecomeCatalogPrices() throws Exception {
        AtomicReference<String> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = response(payload.get());
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var provider = provider(server, Duration.ofSeconds(3));
            for (String invalid : List.of(
                    ASSISTED_DRAFT.replace("\"basePriceMinor\":null", "\"basePriceMinor\":60.5"),
                    ASSISTED_DRAFT.replace("\"priceMinor\":1500", "\"priceMinor\":\"1500\""),
                    ASSISTED_DRAFT.replace("\"title\":", "\"execute\":\"publish\",\"title\":"))) {
                payload.set(invalid);
                assertThatThrownBy(() -> provider.extract(null, "Latte", "opaque-actor"))
                        .isInstanceOf(MenuImportAiException.class);
            }
        } finally { server.stop(0); }
    }

    @Test
    void deadlineAlsoCoversResponseBodyAndReleasesProviderSlot() throws Exception {
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var provider = provider(server, Duration.ofMillis(80));
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> provider.extract(null, "Latte", "opaque-actor"))
                        .isInstanceOf(MenuImportAiException.class).hasMessageContaining("completar");
            }
        } finally { server.stop(0); executor.shutdownNow(); }
    }

    private OpenAiMenuImportProvider provider(HttpServer server, Duration timeout) {
        return new OpenAiMenuImportProvider(objectMapper, HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:%d/v1/responses".formatted(server.getAddress().getPort())),
                true, "test-placeholder-only", "gpt-5.4-mini", timeout, 4000, true);
    }

    private byte[] response(String payload) throws java.io.IOException {
        return objectMapper.writeValueAsBytes(Map.of("status", "completed", "output", List.of(Map.of(
                "type", "message", "content", List.of(Map.of("type", "output_text", "text", payload))))));
    }

    private byte[] wave() {
        return ByteBuffer.allocate(8044).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(8036)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(8000).array();
    }

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
