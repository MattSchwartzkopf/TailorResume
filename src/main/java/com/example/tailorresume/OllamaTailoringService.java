package com.example.tailorresume;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class OllamaTailoringService extends AbstractResumeTailoringService {

    private final HttpClient httpClient;
    private final URI chatUri;
    private final String model;

    public OllamaTailoringService(
            ObjectMapper objectMapper,
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.model}") String model) {
        super(objectMapper);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.chatUri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat");
        this.model = model;
    }

    @Override
    protected String requestModel(
            String resume, String jobDescription, int sourceParagraphCount, String correction, String apiKey)
            throws IOException {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "format", responseFormat(sourceParagraphCount),
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userMessage(resume, jobDescription, correction))));
        HttpRequest request = HttpRequest.newBuilder(chatUri)
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        StringBuilder generatedJson = new StringBuilder();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String error = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                    throw new IOException("Ollama returned HTTP " + response.statusCode() + ": " + error);
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        JsonNode chunk = objectMapper.readTree(line);
                        if (chunk.hasNonNull("error")) {
                            throw new IOException("Ollama error: " + chunk.get("error").asText());
                        }
                        JsonNode content = chunk.path("message").path("content");
                        if (content.isTextual()) {
                            generatedJson.append(content.asText());
                        }
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("The request to Ollama was interrupted.", exception);
        }
        return generatedJson.toString();
    }

}
