package com.example.tailorresume;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api")
public class ResumeController {

    private static final int MAX_JOB_DESCRIPTION_CHARACTERS = 30_000;
    private static final int MAX_RESUME_CHARACTERS = 50_000;

    private final ResumeTextExtractor textExtractor;
    private final DocxTemplateService docxTemplateService;
    private final OllamaTailoringService tailoringService;
    private final OpenAiTailoringService openAiTailoringService;
    private final ResumePdfService pdfService;
    private final String defaultProvider;

    public ResumeController(
            ResumeTextExtractor textExtractor,
            DocxTemplateService docxTemplateService,
            OllamaTailoringService tailoringService,
            OpenAiTailoringService openAiTailoringService,
            ResumePdfService pdfService,
            @Value("${tailoring.provider:ollama}") String defaultProvider) {
        if (!defaultProvider.equals("ollama") && !defaultProvider.equals("openai")) {
            throw new IllegalArgumentException("TAILORING_PROVIDER must be either ollama or openai.");
        }
        this.textExtractor = textExtractor;
        this.docxTemplateService = docxTemplateService;
        this.tailoringService = tailoringService;
        this.openAiTailoringService = openAiTailoringService;
        this.pdfService = pdfService;
        this.defaultProvider = defaultProvider;
    }

    @GetMapping("/settings")
    public AppSettings settings() {
        return new AppSettings(defaultProvider);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResumePreview preview(@RequestParam("resume") MultipartFile resume) throws IOException {
        return new ResumePreview(resume.getOriginalFilename(), textExtractor.extract(resume));
    }

    @PostMapping(value = "/tailor", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TailoredResume> tailor(
            @RequestParam("resume") MultipartFile resume,
            @RequestParam("jobDescription") String jobDescription,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "apiKey", required = false) String apiKey) throws IOException {
        String resumeText = textExtractor.extract(resume);
        validateText(resumeText, jobDescription);
        String selectedProvider = provider == null ? defaultProvider : provider;
        AbstractResumeTailoringService selectedService;
        if (selectedProvider.equals("openai")) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("Enter your OpenAI API key in AI settings before tailoring.");
            }
            if (apiKey.length() > 512) {
                throw new IllegalArgumentException("The OpenAI API key is too long.");
            }
            selectedService = openAiTailoringService;
        } else if (selectedProvider.equals("ollama")) {
            selectedService = tailoringService;
            apiKey = null;
        } else {
            throw new IllegalArgumentException("Choose a supported AI provider in AI settings.");
        }
        String promptText = isDocx(resume)
                ? docxTemplateService.createTaggedPromptText(resume.getBytes())
                : docxTemplateService.createTaggedPlainText(resumeText);
        String edits = selectedService.tailorResume(
                promptText, jobDescription, apiKey == null ? null : apiKey.trim());
        String preview = isDocx(resume)
                ? docxTemplateService.extractText(docxTemplateService.applyEdits(resume.getBytes(), edits))
                : docxTemplateService.applyPlainTextEdits(resumeText, edits);
        return ResponseEntity.ok(new TailoredResume(preview, edits));
    }

    @PostMapping(value = "/export", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> export(
            @RequestParam("resume") MultipartFile resume,
            @RequestParam("tailoredText") String tailoredText,
            @RequestParam(value = "jobDescription", required = false) String jobDescription) throws IOException {
        if (tailoredText == null || tailoredText.isBlank()) {
            throw new IllegalArgumentException("There is no tailored resume to export.");
        }
        if (tailoredText.length() > MAX_RESUME_CHARACTERS) {
            throw new IllegalArgumentException("The tailored resume is too long to export.");
        }
        if (!isDocx(resume)) {
            throw new IllegalArgumentException(
                    "To keep the original layout, upload the resume's original DOCX file. A PDF or TXT cannot be safely edited in place.");
        }
        byte[] updatedDocx = docxTemplateService.applyEdits(resume.getBytes(), tailoredText);
        byte[] pdf = pdfService.convertDocxToPdf(updatedDocx);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(JobDescriptionCompanyExtractor.filenameFor(jobDescription))
                                .build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private boolean isDocx(MultipartFile resume) {
        String filename = resume.getOriginalFilename();
        return filename != null && filename.toLowerCase(java.util.Locale.ROOT).endsWith(".docx");
    }

    private void validateText(String resume, String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            throw new IllegalArgumentException("Paste the job description before tailoring your resume.");
        }
        if (jobDescription.length() > MAX_JOB_DESCRIPTION_CHARACTERS) {
            throw new IllegalArgumentException("The job description is too long (maximum 30,000 characters).");
        }
        if (resume.length() > MAX_RESUME_CHARACTERS) {
            throw new IllegalArgumentException("The resume is too long to tailor.");
        }
    }

    public record ResumePreview(String fileName, String text) {
    }

    public record AppSettings(String defaultProvider) {
    }

    public record TailoredResume(String preview, String edits) {
    }
}
