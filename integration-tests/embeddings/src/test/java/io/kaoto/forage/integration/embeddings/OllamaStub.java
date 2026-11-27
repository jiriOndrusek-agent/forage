package io.kaoto.forage.integration.embeddings;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for Ollama: {@code POST /api/embed} returns one vector per input. Each word of a text
 * counts in one of {@link #DIMENSION} dimensions, so texts that share words are close. The server
 * lives as long as the JVM, like the containers of the other integration tests.
 */
final class OllamaStub {
    static final int DIMENSION = 64;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static OllamaStub instance;

    private final HttpServer server;
    private final List<String> models = new CopyOnWriteArrayList<>();

    private OllamaStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/embed", this::embed);
        server.start();
    }

    static synchronized OllamaStub instance() {
        if (instance == null) {
            try {
                instance = new OllamaStub();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return instance;
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort();
    }

    /** The model names of all requests received so far. */
    List<String> models() {
        return models;
    }

    private void embed(HttpExchange exchange) throws IOException {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        String model = request.path("model").asText();
        models.add(model);

        ObjectNode response = MAPPER.createObjectNode().put("model", model);
        ArrayNode embeddings = response.putArray("embeddings");
        for (JsonNode input : request.path("input")) {
            float[] vector = vector(input.asText());
            ArrayNode values = embeddings.addArray();
            for (float value : vector) {
                values.add(value);
            }
        }

        byte[] body = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static float[] vector(String text) {
        float[] vector = new float[DIMENSION];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (!word.isEmpty()) {
                vector[Math.floorMod(word.hashCode(), DIMENSION)]++;
            }
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        for (int i = 0; norm > 0 && i < DIMENSION; i++) {
            vector[i] /= (float) Math.sqrt(norm);
        }
        return vector;
    }
}
