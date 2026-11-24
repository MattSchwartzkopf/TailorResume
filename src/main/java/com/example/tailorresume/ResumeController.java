package com.example.tailorresume;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final ResumePdfService pdfService;

    public ResumeController(
            ResumeTextExtractor textExtractor,
            DocxTemplateService docxTemplateService,
            OllamaTailoringService tailoringService,
            ResumePdfService pdfService) {
        this.textExtractor = textExtractor;
        this.docxTemplateService = docxTemplateService;
        this.tailoringService = tailoringService;
        this.pdfService = pdfService;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResumePreview preview(@RequestParam("resume") MultipartFile resume) throws IOException {
        return new ResumePreview(resume.getOriginalFilename(), textExtractor.extract(resume));
    }

    @PostMapping(value = "/tailor", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TailoredResume> tailor(
            @RequestParam("resume") MultipartFile resume,
            @RequestParam("jobDescription") String jobDescription) throws IOException {
        String resumeText = textExtractor.extract(resume);
        validateText(resumeText, jobDescription);
        String promptText = isDocx(resume)
                ? docxTemplateService.createTaggedPromptText(resume.getBytes())
                : docxTemplateService.createTaggedPlainText(resumeText);
        String edits = tailoringService.tailorResume(promptText, jobDescription);
        String preview = isDocx(resume)
                ? docxTemplateService.extractText(docxTemplateService.applyEdits(resume.getBytes(), edits))
                : docxTemplateService.applyPlainTextEdits(resumeText, edits);
        return ResponseEntity.ok(new TailoredResume(preview, edits));
    }

    @PostMapping(value = "/export", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> export(
            @RequestParam("resume") MultipartFile resume,
            @RequestParam("tailoredText") String tailoredText) throws IOException {
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
                        ContentDisposition.attachment().filename("tailored-resume.pdf").build().toString())
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

    public record TailoredResume(String preview, String edits) {
    }
}
