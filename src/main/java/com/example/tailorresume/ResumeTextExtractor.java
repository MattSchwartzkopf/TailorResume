package com.example.tailorresume;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class ResumeTextExtractor {

    private static final int MAX_EXTRACTED_CHARACTERS = 50_000;
    private final DocxTemplateService docxTemplateService;

    public ResumeTextExtractor(DocxTemplateService docxTemplateService) {
        this.docxTemplateService = docxTemplateService;
    }

    public String extract(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Choose a PDF, DOCX, or TXT resume.");
        }

        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String text;
        try {
            text = switch (extension) {
                case "pdf" -> extractPdf(file.getBytes());
                case "docx" -> docxTemplateService.extractText(file.getBytes());
                case "txt" -> new String(file.getBytes(), StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("Supported resume formats are PDF, DOCX, and TXT.");
            };
        } catch (IOException exception) {
            throw new IllegalArgumentException("This resume file could not be read. Check that it is a valid PDF, DOCX, or TXT file.", exception);
        }
        text = text.strip();
        if (text.isBlank()) {
            throw new IllegalArgumentException("No readable text was found. If this is a scanned PDF, run OCR on it first.");
        }
        if (text.length() > MAX_EXTRACTED_CHARACTERS) {
            throw new IllegalArgumentException("The extracted resume is too long (maximum 50,000 characters).");
        }
        return text;
    }

    private String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

}
