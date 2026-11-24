package com.example.tailorresume;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeServicesTests {

    private final DocxTemplateService docxTemplateService = new DocxTemplateService();
    private final ResumeTextExtractor textExtractor = new ResumeTextExtractor(docxTemplateService);

    @Test
    void extractsPlainTextResumes() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "resume", "resume.txt", "text/plain", "  Product designer\nPortfolio: example.com  ".getBytes(StandardCharsets.UTF_8));

        assertThat(textExtractor.extract(file)).isEqualTo("Product designer\nPortfolio: example.com");
    }

    @Test
    void rejectsUnsupportedResumeFormats() {
        MockMultipartFile file = new MockMultipartFile("resume", "resume.rtf", "text/plain", new byte[]{1});

        assertThatThrownBy(() -> textExtractor.extract(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supported resume formats");
    }

    @Test
    void usesConfiguredLibreOfficeExecutableWhenPresent() throws Exception {
        Path executable = Files.createTempFile("soffice-test-", ".sh");
        try {
            assertThat(executable.toFile().setExecutable(true)).isTrue();
            LibreOfficeManager manager = new LibreOfficeManager(executable.toString());

            assertThat(manager.resolveExecutable()).isEqualTo(executable.toString());
        } finally {
            Files.deleteIfExists(executable);
        }
    }

    @Test
    void reportsMissingConfiguredLibreOfficeExecutable() {
        LibreOfficeManager manager = new LibreOfficeManager("/missing/path/to/soffice");

        assertThatThrownBy(manager::resolveExecutable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Configured LibreOffice executable was not found");
    }

    @Test
    void discoversWindowsLibreOfficeInStandardInstallationDirectory() throws Exception {
        Path userHome = Files.createTempDirectory("tailor-resume-windows-home-");
        Path programFiles = Files.createTempDirectory("tailor-resume-program-files-");
        Path executable = programFiles.resolve("LibreOffice 26.8/program/soffice.exe");
        try {
            Files.createDirectories(executable.getParent());
            Files.createFile(executable);
            LibreOfficeManager manager = new LibreOfficeManager(
                    "soffice", "Windows 11", null, userHome.toString(), programFiles.toString(), null, null);

            assertThat(manager.resolveExecutable()).isEqualTo(executable.toString());
        } finally {
            deleteTestDirectory(userHome);
            deleteTestDirectory(programFiles);
        }
    }

    @Test
    void mapsWindowsArchitecturesToLibreOfficeDownloadNames() {
        assertThat(LibreOfficeManager.windowsArchitecture("amd64")).isEqualTo("x86_64");
        assertThat(LibreOfficeManager.windowsArchitecture("aarch64")).isEqualTo("aarch64");
        assertThat(LibreOfficeManager.windowsArchitecture("x86")).isEqualTo("x86");
        assertThat(LibreOfficeManager.windowsArchitecture("mips")).isNull();
    }

    @Test
    void convertsStructuredAiEditsToOriginalParagraphIds() throws Exception {
        OllamaTailoringService service = new OllamaTailoringService(
                new ObjectMapper(), "http://localhost:11434", "test-model");
        String source = "[[P0000]] Name\n[[P0001]] Summary\n[[P0002]] Experience";
        String json = """
                {"paragraphs":[
                  {"id":2,"text":"Relevant experience"},
                  {"id":0,"text":"Candidate name"}
                ]}
                """;

        assertThat(service.formatIndexedParagraphs(json, source))
                .isEqualTo("[[P0000]] Candidate name\n[[P0002]] Relevant experience\n");
    }

    @Test
    void rejectsStructuredAiEditsWithUnknownOrDuplicateParagraphIds() {
        OllamaTailoringService service = new OllamaTailoringService(
                new ObjectMapper(), "http://localhost:11434", "test-model");
        String source = "[[P0000]] Name";

        assertThatThrownBy(() -> service.formatIndexedParagraphs(
                "{\"paragraphs\":[{\"id\":1,\"text\":\"Wrong paragraph\"}]}", source))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("invalid or duplicate");
        assertThatThrownBy(() -> service.formatIndexedParagraphs(
                "{\"paragraphs\":[{\"id\":0,\"text\":\"One\"},{\"id\":0,\"text\":\"Two\"}]}", source))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("invalid or duplicate");
    }

    @Test
    void retriesWhenOllamaReturnsDuplicateParagraphIds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger requestCount = new AtomicInteger();
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            int currentRequest = requestCount.incrementAndGet();
            String content = currentRequest == 1
                    ? "{\"paragraphs\":[{\"id\":0,\"text\":\"One\"},{\"id\":0,\"text\":\"Duplicate\"}]}"
                    : "{\"paragraphs\":[{\"id\":1,\"text\":\"Updated experience\"}]}";
            byte[] response = (mapper.writeValueAsString(Map.of(
                    "message", Map.of("content", content),
                    "done", true)) + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            OllamaTailoringService service = new OllamaTailoringService(
                    mapper, "http://127.0.0.1:" + server.getAddress().getPort(), "test-model");
            String tailored = service.tailorResume(
                    "[[P0000]] Candidate\n[[P0001]] Original experience", "Second job description");

            assertThat(requestCount).hasValue(2);
            assertThat(tailored)
                    .isEqualTo("[[P0001]] Updated experience\n");
            assertThat(requests.get(1)).contains("REQUIRED CORRECTION", "at most once");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsOpenAiRequestsWithUserKeyAndReturnsStructuredParagraphEdits() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger requestCount = new AtomicInteger();
        List<String> authorizationHeaders = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestCount.incrementAndGet();
            byte[] response = mapper.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("message", Map.of(
                            "content", "{\"paragraphs\":[{\"id\":1,\"text\":\"Tailored experience\"}]}")))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            OpenAiTailoringService service = new OpenAiTailoringService(
                    mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model");
            String tailored = service.tailorResume(
                    "[[P0000]] Candidate\n[[P0001]] Original experience", "Job description", "test-api-key");

            assertThat(requestCount).hasValue(1);
            assertThat(authorizationHeaders).containsExactly("Bearer test-api-key");
            assertThat(requests.get(0))
                    .contains("\"model\":\"test-model\"", "\"response_format\":{\"type\":\"json_object\"}",
                            "Original experience", "Job description")
                    .doesNotContain("test-api-key");
            assertThat(tailored).isEqualTo("[[P0001]] Tailored experience\n");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void editsNarrativeWithoutChangingHeadingsOrParagraphAndFontStyles() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var paragraph = document.createParagraph();
            var heading = paragraph.createRun();
            heading.setText("Original heading");
            heading.setBold(true);
            heading.setFontSize(18);
            var body = document.createParagraph();
            body.setStyle("Normal");
            var emphasized = body.createRun();
            emphasized.setText("Original professional experience summary describes ");
            emphasized.setBold(true);
            body.createRun().setText("relevant work across several teams and projects. ".repeat(4));
            document.createTable(1, 1).getRow(0).getCell(0)
                    .getParagraphs().get(0).createRun().setText("Original table text");
            document.write(output);
            source = output.toByteArray();
        }

        String taggedText = docxTemplateService.createTaggedPromptText(source);
        assertThat(taggedText).contains(
                "Original heading",
                "[[P0000]] Original professional experience summary",
                "Original table text");
        assertThat(taggedText).doesNotContain("[[P0001]]");

        byte[] updated = docxTemplateService.applyEdits(
                source, "[[P0000]] Relevant professional experience summary describes "
                        + "important work across several teams and projects. ".repeat(4));
        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(updated))) {
            assertThat(document.getParagraphs().get(0).getText()).isEqualTo("Original heading");
            assertThat(document.getParagraphs().get(0).getRuns().get(0).isBold()).isTrue();
            assertThat(document.getParagraphs().get(0).getRuns().get(0).getFontSize()).isEqualTo(18);
            assertThat(document.getParagraphs().get(1).getText()).contains("Relevant professional experience");
            assertThat(document.getParagraphs().get(1).getStyle()).isEqualTo("Normal");
            assertThat(document.getParagraphs().get(1).getRuns().get(0).isBold()).isTrue();
            assertThat(document.getParagraphs().get(1).getRuns().get(1).isBold()).isFalse();
            assertThat(document.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0).getText())
                    .isEqualTo("Original table text");
        }
    }

    @Test
    void summaryLabelCannotBeReplacedByGeneratedSummaryText() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = document.createTable(1, 2).getRow(0);
            row.getCell(0).getParagraphs().get(0).createRun().setText("SUMMARY");
            row.getCell(1).getParagraphs().get(0).createRun()
                    .setText(longNarrative("Original software engineering summary"));
            document.write(output);
            source = output.toByteArray();
        }

        String taggedText = docxTemplateService.createTaggedPromptText(source);
        assertThat(taggedText).contains("[[P0000]] Original software engineering summary");
        assertThat(taggedText).doesNotContain("[[P0000]] SUMMARY");

        byte[] updated = docxTemplateService.applyEdits(
                source, "[[P0000]] " + longNarrative("Tailored software engineering summary"));
        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(updated))) {
            assertThat(document.getTables().get(0).getRow(0).getCell(0).getText()).isEqualTo("SUMMARY");
            assertThat(document.getTables().get(0).getRow(0).getCell(1).getText())
                    .contains("Tailored software engineering summary");
        }
    }

    @Test
    void replacesLongTextAcrossManyStyledRunsWithoutInvalidRanges() throws Exception {
        String replacement = "Led multiple cross-functional projects that improved delivery speed, "
                + "reduced operational overhead, and increased customer satisfaction by measurable amounts.";
        byte[] source;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var paragraph = document.createParagraph();
            for (int i = 0; i < 240; i++) {
                var run = paragraph.createRun();
                run.setText(i == 239 ? "x." : "x");
                if (i % 2 == 0) {
                    run.setBold(true);
                }
            }
            document.write(output);
            source = output.toByteArray();
        }

        byte[] updated = docxTemplateService.applyEdits(source, "[[P0000]] " + replacement);

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(updated))) {
            assertThat(document.getParagraphs().get(0).getText()).isEqualTo(replacement);
        }
    }

    @Test
    void preservesOriginalParagraphsWhenStructuredAiEditsOmitThem() throws Exception {
        byte[] source = createTwoParagraphTemplate();

        byte[] updated = docxTemplateService.applyEdits(source, "[[P0000]] " + longNarrative("Only one paragraph"));

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(updated))) {
            assertThat(document.getParagraphs().get(0).getText()).isEqualTo("Original heading");
            assertThat(document.getParagraphs().get(1).getText()).contains("Only one paragraph");
        }
    }

    @Test
    void rejectsUnstructuredOutputRatherThanMisplacingIt() throws Exception {
        byte[] source = createTwoParagraphTemplate();

        assertThatThrownBy(() -> docxTemplateService.applyEdits(
                source, "Tailored heading\nRelevant experience"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no paragraph-linked");
    }

    @Test
    void createsIndexedPromptParagraphsForTextResumeFormats() {
        assertThat(docxTemplateService.createTaggedPlainText("Name\n\nBackend Engineer\nBuilt services"))
                .isEqualTo("[[P0000]] Name\n[[P0001]] Backend Engineer\n[[P0002]] Built services");
    }

    @Test
    void reconstructsPlainTextPreviewWithUneditedLinesAndSpacing() {
        String source = "Name\n\nBackend Engineer\nBuilt services";
        String preview = docxTemplateService.applyPlainTextEdits(
                source, "[[P0002]] Built reliable services");

        assertThat(preview).isEqualTo("Name\n\nBackend Engineer\nBuilt reliable services");
    }

    @Test
    void appliesStructuredEditsToTheirOriginalParagraphs() throws Exception {
        byte[] source = createTwoParagraphTemplate();

        byte[] updated = docxTemplateService.applyEdits(
                source, "[[P0000]] " + longNarrative("Relevant experience"));

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(updated))) {
            assertThat(document.getParagraphs().get(0).getText()).isEqualTo("Original heading");
            assertThat(document.getParagraphs().get(1).getText()).contains("Relevant experience");
        }
    }

    @Test
    void acceptsNonPaddedParagraphMarkers() throws Exception {
        byte[] source = createTwoParagraphTemplate();

        byte[] updated = docxTemplateService.applyEdits(
                source, "[[P0]] " + longNarrative("Relevant experience"));

        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(updated))) {
            assertThat(document.getParagraphs().get(0).getText()).isEqualTo("Original heading");
            assertThat(document.getParagraphs().get(1).getText()).contains("Relevant experience");
        }
    }

    private byte[] createTwoParagraphTemplate() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Original heading");
            document.createParagraph().createRun().setText(longNarrative("Original experience"));
            document.write(output);
            return output.toByteArray();
        }
    }

    private String longNarrative(String beginning) {
        return beginning + " supported a range of successful projects with measurable results. ".repeat(4);
    }

    private void deleteTestDirectory(Path directory) throws Exception {
        if (Files.exists(directory)) {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
