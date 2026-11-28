package com.example.tailorresume;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiTailoringService extends AbstractResumeTailoringService {

    private final HttpClient httpClient;
    private final URI chatUri;
    private final String model;

    public OpenAiTailoringService(
            ObjectMapper objectMapper,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.model:gpt-4o-mini}") String model) {
        super(objectMapper);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.chatUri = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.model = model;
    }

    @Override
    protected String requestModel(
            String resume, String jobDescription, int sourceParagraphCount, String correction, String apiKey)
            throws IOException {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "response_format", Map.of("type", "json_object"),
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userMessage(resume, jobDescription, correction))));
        HttpRequest request = HttpRequest.newBuilder(chatUri)
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                String responseText = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                JsonNode responseJson = objectMapper.readTree(responseText);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String message = responseJson.path("error").path("message").asText("Request failed.");
                    throw new IOException("OpenAI returned HTTP " + response.statusCode() + ": " + message);
                }
                JsonNode content = responseJson.path("choices").path(0).path("message").path("content");
                if (!content.isTextual()) {
                    throw new IOException("OpenAI returned an empty or invalid response.");
                }
                return content.textValue();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("The request to OpenAI was interrupted.", exception);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IOException("OpenAI returned an invalid response.", exception);
        }
    }
}
