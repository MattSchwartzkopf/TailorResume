package com.example.tailorresume;

import org.springframework.beans.factory.annotation.Autowired;
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
    private static final long MINIMUM_MSI_BYTES = 200_000_000;

    private final String configuredExecutable;
    private final String operatingSystem;
    private final String pathValue;
    private final String userHome;
    private final String programFiles;
    private final String programFilesX86;
    private final String localAppData;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Autowired
    public LibreOfficeManager(
            @Value("${libreoffice.executable:${LIBREOFFICE_EXECUTABLE:soffice}}") String configuredExecutable) {
        this(configuredExecutable, System.getProperty("os.name"), System.getenv("PATH"),
                System.getProperty("user.home"), System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA"));
    }

    LibreOfficeManager(
            String configuredExecutable, String operatingSystem, String pathValue, String userHome,
            String programFiles, String programFilesX86, String localAppData) {
        this.configuredExecutable = configuredExecutable;
        this.operatingSystem = operatingSystem.toLowerCase(java.util.Locale.ROOT);
        this.pathValue = pathValue;
        this.userHome = userHome;
        this.programFiles = programFiles;
        this.programFilesX86 = programFilesX86;
        this.localAppData = localAppData;
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
        if (isWindows()) {
            return installForCurrentWindows();
        }
        if (isMac()) {
            return installForCurrentMac();
        }
        throw new IllegalStateException("LibreOffice is required for formatted PDF export. Install it locally and ensure "
                + executableName() + " is on PATH, or set LIBREOFFICE_EXECUTABLE.");
    }

    private String findExecutable() throws IOException {
        if (pathValue != null) {
            for (String directory : pathValue.split(java.io.File.pathSeparator)) {
                for (String executableName : executableNames()) {
                    Path executable = Path.of(directory, executableName);
                    if (Files.isRegularFile(executable) && (isWindows() || Files.isExecutable(executable))) {
                        return executable.toAbsolutePath().toString();
                    }
                }
            }
        }
        for (Path executable : installedExecutableCandidates()) {
            if (Files.isRegularFile(executable) && (isWindows() || Files.isExecutable(executable))) {
                return executable.toString();
            }
        }
        return null;
    }

    private List<Path> installedExecutableCandidates() throws IOException {
        if (isWindows()) {
            List<Path> candidates = new java.util.ArrayList<>();
            addWindowsInstallCandidates(candidates, programFiles);
            addWindowsInstallCandidates(candidates, programFilesX86);
            if (localAppData != null && !localAppData.isBlank()) {
                candidates.add(Path.of(localAppData, "Programs", "LibreOffice", "program", "soffice.exe"));
            }
            Path userInstall = windowsExecutableUnder(installDirectory().resolve("LibreOffice"));
            if (userInstall != null) {
                candidates.add(userInstall);
            }
            return candidates;
        }
        return List.of(
                Path.of("/Applications/LibreOffice.app/Contents/MacOS/soffice"),
                Path.of(userHome, "Applications/LibreOffice.app/Contents/MacOS/soffice"),
                installDirectory().resolve("LibreOffice.app/Contents/MacOS/soffice"));
    }

    private void addWindowsInstallCandidates(List<Path> candidates, String baseDirectory) throws IOException {
        if (baseDirectory == null || baseDirectory.isBlank()) {
            return;
        }
        Path base = Path.of(baseDirectory);
        candidates.add(base.resolve("LibreOffice/program/soffice.exe"));
        if (!Files.isDirectory(base)) {
            return;
        }
        try (Stream<Path> entries = Files.list(base)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .startsWith("libreoffice"))
                    .map(path -> path.resolve("program/soffice.exe"))
                    .forEach(candidates::add);
        }
    }

    private Path windowsExecutableUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("soffice.exe"))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equalsIgnoreCase("program"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private List<String> executableNames() {
        return isWindows() ? List.of("soffice.exe", "soffice.com", "soffice") : List.of("soffice");
    }

    private String executableName() {
        return isWindows() ? "soffice.exe" : "soffice";
    }

    private boolean isWindows() {
        return operatingSystem.contains("windows");
    }

    private boolean isMac() {
        return operatingSystem.contains("mac");
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

    private String installForCurrentWindows() throws IOException {
        String architecture = windowsArchitecture(System.getProperty("os.arch"));
        if (architecture == null) {
            throw new IllegalStateException(
                    "Automatic LibreOffice setup does not support this Windows architecture: "
                            + System.getProperty("os.arch"));
        }

        Path toolsDirectory = installDirectory();
        Path installedDirectory = toolsDirectory.resolve("LibreOffice");
        Path installedExecutable = windowsExecutableUnder(installedDirectory);
        if (installedExecutable != null && Files.isRegularFile(installedExecutable)) {
            return installedExecutable.toString();
        }
        Files.createDirectories(toolsDirectory);
        Path temporaryDirectory = Files.createTempDirectory(toolsDirectory, "libreoffice-setup-");
        Path extractedDirectory = temporaryDirectory.resolve("extracted");
        Files.createDirectory(extractedDirectory);
        try {
            String version = latestStableVersion();
            String filename = "LibreOffice_" + version + "_Win_" + architecture + ".msi";
            URI downloadUri = URI.create("https://download.documentfoundation.org/libreoffice/stable/"
                    + version + "/win/" + architecture + "/" + filename);
            Path installer = temporaryDirectory.resolve(filename);
            download(downloadingRequest(downloadUri), installer);
            if (Files.size(installer) < MINIMUM_MSI_BYTES) {
                throw new IOException("The LibreOffice download was incomplete or invalid.");
            }
            verifyWindowsInstallerSignature(installer, temporaryDirectory);

            Path log = temporaryDirectory.resolve("extract.log");
            runProcess(List.of("msiexec.exe", "/a", installer.toString(), "/qn",
                            "TARGETDIR=" + extractedDirectory),
                    log, Duration.ofMinutes(10),
                    "Could not extract LibreOffice into the user's application data directory.");

            Path extractedExecutable = windowsExecutableUnder(extractedDirectory);
            if (extractedExecutable == null) {
                throw new IOException("The LibreOffice installer did not contain its PDF converter executable.");
            }
            Path relativeExecutable = extractedDirectory.relativize(extractedExecutable);
            if (Files.exists(installedDirectory)) {
                deleteTree(installedDirectory);
            }
            try {
                Files.move(extractedDirectory, installedDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(extractedDirectory, installedDirectory);
            }
            Path executable = installedDirectory.resolve(relativeExecutable);
            if (!Files.isRegularFile(executable)) {
                throw new IOException("LibreOffice was extracted, but its PDF converter executable was not found.");
            }
            return executable.toString();
        } finally {
            deleteTree(temporaryDirectory);
        }
    }

    static String windowsArchitecture(String architecture) {
        return switch (architecture.toLowerCase(java.util.Locale.ROOT)) {
            case "aarch64", "arm64" -> "aarch64";
            case "x86_64", "amd64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> null;
        };
    }

    private void verifyWindowsInstallerSignature(Path installer, Path temporaryDirectory) throws IOException {
        String escapedPath = installer.toAbsolutePath().toString().replace("'", "''");
        String verifyCommand = "$signature = Get-AuthenticodeSignature -LiteralPath '"
                + escapedPath + "'; "
                + "if ($signature.Status -ne 'Valid' -or "
                + "$signature.SignerCertificate.Subject -notmatch 'The Document Foundation') { exit 1 }";
        runProcess(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", verifyCommand),
                temporaryDirectory.resolve("signature.log"), Duration.ofMinutes(2),
                "The downloaded LibreOffice installer failed publisher signature verification.");
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
        return Path.of(userHome, ".tailor-resume", "tools");
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
