package com.example.tailorresume;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Service
public class ResumePdfService {

    private final LibreOfficeManager libreOfficeManager;

    public ResumePdfService(LibreOfficeManager libreOfficeManager) {
        this.libreOfficeManager = libreOfficeManager;
    }

    public byte[] convertDocxToPdf(byte[] docx) throws IOException {
        Path workDirectory = Files.createTempDirectory("tailor-resume-");
        try {
            Path input = workDirectory.resolve("tailored-resume.docx");
            Path log = workDirectory.resolve("converter.log");
            Files.write(input, docx);
            String libreOfficeExecutable;
            try {
                libreOfficeExecutable = libreOfficeManager.resolveExecutable();
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Could not set up LibreOffice for PDF export. Check your internet connection and try again.", exception);
            }
            Process process;
            try {
                process = new ProcessBuilder(
                        libreOfficeExecutable,
                        "--headless",
                        "--convert-to", "pdf",
                        "--outdir", workDirectory.toString(),
                        input.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "To export a formatted DOCX resume as PDF, install LibreOffice or set LIBREOFFICE_EXECUTABLE.", exception);
            }
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("PDF conversion timed out. Try again or check the LibreOffice installation.");
            }
            Path output = workDirectory.resolve("tailored-resume.pdf");
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                String details = Files.exists(log) ? Files.readString(log).strip() : "";
                throw new IllegalStateException("LibreOffice could not convert the tailored resume to PDF."
                        + (details.isBlank() ? "" : " " + details));
            }
            return Files.readAllBytes(output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("PDF conversion was interrupted.", exception);
        } finally {
            try (var files = Files.walk(workDirectory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

}
