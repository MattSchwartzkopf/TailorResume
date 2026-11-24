package com.example.tailorresume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(name = "libreoffice.auto-install", havingValue = "true", matchIfMissing = true)
public class LibreOfficeSetupRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LibreOfficeSetupRunner.class);
    private final LibreOfficeManager libreOfficeManager;

    public LibreOfficeSetupRunner(LibreOfficeManager libreOfficeManager) {
        this.libreOfficeManager = libreOfficeManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        CompletableFuture.runAsync(() -> {
            try {
                String executable = libreOfficeManager.resolveExecutable();
                logger.info("Local PDF formatter is ready: {}", executable);
            } catch (IOException | IllegalStateException exception) {
                logger.warn("Could not prepare LibreOffice for formatted PDF export at startup. "
                        + "The app will retry when a PDF is requested: {}", exception.getMessage());
            }
        });
    }
}
