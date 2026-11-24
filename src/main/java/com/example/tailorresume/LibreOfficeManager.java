package com.example.tailorresume;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class LibreOfficeManager {

    private static final URI STABLE_RELEASES = URI.create("https://download.documentfoundation.org/libreoffice/stable/");
    private static final Pattern RELEASE_LINK = Pattern.compile("href=\"(\\d+\\.\\d+\\.\\d+)/\"");
    private static final long MINIMUM_DMG_BYTES = 100_000_000;

    private final String configuredExecutable;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public LibreOfficeManager(
            @Value("${libreoffice.executable:${LIBREOFFICE_EXECUTABLE:soffice}}") String configuredExecutable) {
        this.configuredExecutable = configuredExecutable;
    }

    public synchronized String resolveExecutable() throws IOException {
        if (!configuredExecutable.equals("soffice")) {
            if (Files.isExecutable(Path.of(configuredExecutable))) {
                return configuredExecutable;
            }
            throw new IllegalStateException("Configured LibreOffice executable was not found: " + configuredExecutable);
        }

        String discovered = findExecutable();
        if (discovered != null) {
            return discovered;
        }
        if (!System.getProperty("os.name").toLowerCase().contains("mac")) {
            throw new IllegalStateException(
                    "LibreOffice is required for formatted PDF export. Install it locally and ensure 'soffice' is on PATH.");
        }
        return installForCurrentMac();
    }

    private String findExecutable() {
        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (String directory : pathValue.split(java.io.File.pathSeparator)) {
                Path executable = Path.of(directory, "soffice");
                if (Files.isExecutable(executable)) {
                    return executable.toAbsolutePath().toString();
                }
            }
        }
        for (Path executable : List.of(
                Path.of("/Applications/LibreOffice.app/Contents/MacOS/soffice"),
                Path.of(System.getProperty("user.home"), "Applications/LibreOffice.app/Contents/MacOS/soffice"),
                installDirectory().resolve("LibreOffice.app/Contents/MacOS/soffice"))) {
            if (Files.isExecutable(executable)) {
                return executable.toString();
            }
        }
        return null;
    }

    private String installForCurrentMac() throws IOException {
        String architecture = System.getProperty("os.arch").toLowerCase();
        String macArchitecture = architecture.equals("aarch64") || architecture.equals("arm64") ? "aarch64"
                : architecture.equals("x86_64") || architecture.equals("amd64") ? "x86_64" : null;
        if (macArchitecture == null) {
            throw new IllegalStateException("Automatic LibreOffice setup does not support this Mac architecture: " + architecture);
        }

        Path installDirectory = installDirectory();
        Path installedBundle = installDirectory.resolve("LibreOffice.app");
        if (Files.isExecutable(installedBundle.resolve("Contents/MacOS/soffice"))) {
            return installedBundle.resolve("Contents/MacOS/soffice").toString();
        }
        Files.createDirectories(installDirectory);
        Path temporaryDirectory = Files.createTempDirectory(installDirectory, "libreoffice-setup-");
        Path mountPoint = temporaryDirectory.resolve("mounted");
        Files.createDirectory(mountPoint);
        boolean mounted = false;
        try {
            String version = latestStableVersion();
            String filename = "LibreOffice_" + version + "_MacOS_" + macArchitecture + ".dmg";
            URI downloadUri = URI.create("https://download.documentfoundation.org/libreoffice/stable/"
                    + version + "/mac/" + macArchitecture + "/" + filename);
            Path dmg = temporaryDirectory.resolve(filename);
            download(downloadingRequest(downloadUri), dmg);
            if (Files.size(dmg) < MINIMUM_DMG_BYTES) {
                throw new IOException("The LibreOffice download was incomplete or invalid.");
            }

            runProcess(List.of("/usr/bin/hdiutil", "attach", "-quiet", "-readonly", "-nobrowse",
                    "-mountpoint", mountPoint.toString(), dmg.toString()), temporaryDirectory.resolve("mount.log"),
                    Duration.ofMinutes(2), "Could not mount the LibreOffice installer.");
            mounted = true;
            Path sourceBundle;
            try (Stream<Path> mountedFiles = Files.list(mountPoint)) {
                sourceBundle = mountedFiles
                        .filter(path -> path.getFileName().toString().equals("LibreOffice.app"))
                        .findFirst()
                        .orElseThrow(() -> new IOException("The LibreOffice installer did not contain its application bundle."));
            }

            Path stagedBundle = temporaryDirectory.resolve("LibreOffice.app");
            runProcess(List.of("/usr/bin/ditto", sourceBundle.toString(), stagedBundle.toString()),
                    temporaryDirectory.resolve("copy.log"), Duration.ofMinutes(5),
                    "Could not install LibreOffice in the user's application data directory.");
            runProcess(List.of("/usr/bin/codesign", "--verify", "--deep", "--strict", stagedBundle.toString()),
                    temporaryDirectory.resolve("signature.log"), Duration.ofMinutes(2),
                    "The downloaded LibreOffice application failed macOS code-signature verification.");

            if (Files.exists(installedBundle)) {
                deleteTree(installedBundle);
            }
            Files.move(stagedBundle, installedBundle, StandardCopyOption.ATOMIC_MOVE);
            Path executable = installedBundle.resolve("Contents/MacOS/soffice");
            if (!Files.isExecutable(executable)) {
                throw new IOException("LibreOffice installed, but its PDF converter executable was not found.");
            }
            return executable.toString();
        } finally {
            if (mounted) {
                runProcess(List.of("/usr/bin/hdiutil", "detach", "-quiet", mountPoint.toString()),
                        temporaryDirectory.resolve("unmount.log"), Duration.ofMinutes(2),
                        "Could not safely unmount the LibreOffice installer.");
            }
            deleteTree(temporaryDirectory);
        }
    }

    private HttpRequest downloadingRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(20))
                .GET()
                .build();
    }

    private String latestStableVersion() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(STABLE_RELEASES)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("Could not check the official LibreOffice release list (HTTP "
                        + response.statusCode() + ").");
            }
            Matcher matcher = RELEASE_LINK.matcher(response.body());
            String latest = null;
            while (matcher.find()) {
                String version = matcher.group(1);
                if (latest == null || compareVersions(version, latest) > 0) {
                    latest = version;
                }
            }
            if (latest == null) {
                throw new IOException("Could not find a stable LibreOffice release.");
            }
            return latest;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Checking LibreOffice releases was interrupted.", exception);
        }
    }

    private int compareVersions(String first, String second) {
        int[] firstParts = java.util.Arrays.stream(first.split("\\.")).mapToInt(Integer::parseInt).toArray();
        int[] secondParts = java.util.Arrays.stream(second.split("\\.")).mapToInt(Integer::parseInt).toArray();
        for (int i = 0; i < Math.min(firstParts.length, secondParts.length); i++) {
            int comparison = Integer.compare(firstParts[i], secondParts[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(firstParts.length, secondParts.length);
    }

    private void download(HttpRequest request, Path destination) throws IOException {
        try {
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
            if (response.statusCode() != 200) {
                throw new IOException("The official LibreOffice download failed (HTTP " + response.statusCode() + ").");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("The LibreOffice download was interrupted.", exception);
        }
    }

    private void runProcess(List<String> command, Path log, Duration timeout, String failureMessage) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
        } catch (IOException exception) {
            throw new IOException(failureMessage, exception);
        }
        try {
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(failureMessage + " The operation timed out.");
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(failureMessage + " The operation was interrupted.", exception);
        }
        if (process.exitValue() != 0) {
            String details = Files.exists(log) ? Files.readString(log).strip() : "";
            throw new IOException(failureMessage + (details.isBlank() ? "" : " " + details));
        }
    }

    private Path installDirectory() {
        return Path.of(System.getProperty("user.home"), ".tailor-resume", "tools");
    }

    private void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
