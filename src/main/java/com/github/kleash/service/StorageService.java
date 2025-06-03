package com.github.kleash.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class StorageService {

    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);

    @Value("${file.comparison.storage.base-path}")
    private String basePathStringConfig; // Renamed to avoid conflict
    private Path baseStoragePath;

    public Path getBaseStoragePath() { // Getter for controller
        return baseStoragePath;
    }

    @PostConstruct
    public void init() {
        try {
            baseStoragePath = Path.of(basePathStringConfig).toAbsolutePath().normalize();
            Files.createDirectories(baseStoragePath);
            logger.info("Base storage directory created/ensured at: {}", baseStoragePath);
        } catch (IOException e) {
            logger.error("Could not initialize storage location: {}", basePathStringConfig, e);
            throw new RuntimeException("Could not initialize storage location", e);
        }
    }

    public Path createSessionDirectory() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        Path sessionPath = baseStoragePath.resolve(sessionId);
        Files.createDirectories(sessionPath);
        logger.info("Created session directory: {}", sessionPath);
        return sessionPath;
    }

    public Path storeUploadedFile(MultipartFile file, Path sessionPath) throws IOException {
        if (file.isEmpty()) {
            throw new IOException("Failed to store empty file.");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.contains("..")) {
            throw new IOException("Security: Cannot store file with relative path outside current directory: " + originalFilename);
        }
        Path destinationFile = sessionPath.resolve(Path.of(originalFilename)).normalize().toAbsolutePath();
        if (!destinationFile.getParent().equals(sessionPath.toAbsolutePath())) {
            throw new IOException("Security: Cannot store file outside session directory: " + originalFilename);
        }

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Stored uploaded file: {}", destinationFile);
            return destinationFile;
        }
    }

    public Path storeReport(String csvContent, String reportFileName, Path sessionPath) throws IOException {
        Path reportFilePath = sessionPath.resolve(reportFileName).normalize().toAbsolutePath();
        if (!reportFilePath.getParent().equals(sessionPath.toAbsolutePath())) {
            throw new IOException("Security: Cannot store report outside session directory: " + reportFileName);
        }
        Files.writeString(reportFilePath, csvContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        logger.info("Stored report file: {}", reportFilePath);
        return reportFilePath;
    }

    public Path createZipFromReports(Path sessionPath, String zipFileName) throws IOException {
        Path zipFilePath = sessionPath.resolve(zipFileName).normalize().toAbsolutePath();
        if (!zipFilePath.getParent().equals(sessionPath.toAbsolutePath())) {
            throw new IOException("Security: Cannot create ZIP outside session directory " + zipFileName);
        }

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFilePath));
             Stream<Path> paths = Files.walk(sessionPath)) {

            paths.filter(path -> !Files.isDirectory(path))
                    .filter(path -> path.toString().toLowerCase().endsWith(".csv"))
                    .forEach(path -> {
                        ZipEntry zipEntry = new ZipEntry(sessionPath.relativize(path).toString());
                        try {
                            zos.putNextEntry(zipEntry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            logger.error("Error while adding file {} to zip: {}", path, e.getMessage());
                        }
                    });
        }
        logger.info("Created ZIP file: {}", zipFilePath);
        return zipFilePath;
    }

    public String getRelativePathForClient(Path fullPath, Path sessionPath) {
        if (fullPath == null || sessionPath == null) return null;
        // Returns path relative to the sessionPath. e.g. report.csv
        try {
            return sessionPath.relativize(fullPath).toString().replace("\\", "/"); // Ensure forward slashes
        } catch (IllegalArgumentException e) {
            // If fullPath is not under sessionPath, this could happen.
            // Fallback or log error. For reports, they should always be under sessionPath.
            logger.warn("Could not create relative path for {} under {}. Fallback to filename.", fullPath, sessionPath);
            return fullPath.getFileName().toString();
        }
    }

    public void deleteSessionDirectory(Path sessionPath) {
        if (sessionPath != null && Files.exists(sessionPath) && sessionPath.startsWith(baseStoragePath)) {
            try {
                FileSystemUtils.deleteRecursively(sessionPath);
                logger.info("Deleted session directory: {}", sessionPath);
            } catch (IOException e) {
                logger.error("Could not delete session directory {}: {}", sessionPath, e.getMessage());
            }
        } else {
            logger.warn("Attempted to delete invalid or non-existent session directory: {}", sessionPath);
        }
    }
}