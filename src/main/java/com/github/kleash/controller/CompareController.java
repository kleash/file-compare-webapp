package com.github.kleash.controller;

import com.github.kleash.dto.ComparisonResponse;
import com.github.kleash.dto.ManualPair;
import com.github.kleash.dto.OverallMetrics; // For constructing error responses
import com.github.kleash.service.CompareService;
import com.github.kleash.service.StorageService;
import com.github.kleash.service.ComparisonLoggingService; // Import for logging
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest; // For User-Agent and other request details
import javax.servlet.http.HttpSession;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors; // For Collectors.toList() if needed
import java.util.stream.Stream;

@Controller
public class CompareController {
    private static final Logger logger = LoggerFactory.getLogger(CompareController.class);

    @Autowired
    private CompareService compareService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private ComparisonLoggingService loggingService; // Autowire the logging service

    @Autowired
    private ObjectMapper objectMapper; // For parsing manualPairs JSON

    // Key to store the ABSOLUTE path of the session directory in HTTP session
    private static final String LAST_COMPARISON_SESSION_PATH_KEY = "lastComparisonSessionPath";

    @GetMapping("/")
    public String index(Model model) {
        return "index"; // Serves your main comparison tool page (e.g., index.html from templates)
    }

    @PostMapping(value = "/compare", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<ComparisonResponse> compare(
            @RequestParam(value = "source1Files", required = false) MultipartFile[] source1FilesArr,
            @RequestParam(value = "source2Files", required = false) MultipartFile[] source2FilesArr,
            @RequestParam(value = "sortFiles", defaultValue = "false") boolean sortFiles,
            @RequestParam(value = "manualPairs", required = false) String manualPairsJson,
            HttpServletRequest httpRequest, // Injected to get request details like User-Agent
            HttpSession httpSession) {

        long startTime = System.currentTimeMillis();
        Path sessionPath = null; // Will store the absolute path to the session's storage directory
        String relativeSessionPathForZip = null; // For logging the potential zip path

        // Filter out empty MultipartFile objects if no file is selected in a dropzone
        List<MultipartFile> source1Files = (source1FilesArr != null) ?
                Stream.of(source1FilesArr).filter(f -> f != null && !f.isEmpty()).collect(Collectors.toList()) :
                Collections.emptyList();
        List<MultipartFile> source2Files = (source2FilesArr != null) ?
                Stream.of(source2FilesArr).filter(f -> f != null && !f.isEmpty()).collect(Collectors.toList()) :
                Collections.emptyList();

        if (source1Files.isEmpty() && source2Files.isEmpty()) {
            logger.warn("Compare attempt with no valid files provided.");
            OverallMetrics emptyMetrics = new OverallMetrics();
            return ResponseEntity.badRequest().body(new ComparisonResponse(emptyMetrics, Collections.emptyList(), null));
        }

        List<ManualPair> manualPairs = null;
        if (manualPairsJson != null && !manualPairsJson.isEmpty() && !manualPairsJson.equals("[]")) {
            try {
                manualPairs = objectMapper.readValue(manualPairsJson, new TypeReference<List<ManualPair>>() {});
                logger.info("Received {} manual pairs from UI: {}", manualPairs.size(), manualPairsJson);
            } catch (IOException e) {
                logger.error("Error parsing manualPairs JSON: '{}'. Error: {}", manualPairsJson, e.getMessage());
                // Proceed without manual pairs if JSON is malformed, or return bad request
            }
        }

        try {
            sessionPath = storageService.createSessionDirectory();
            logger.info("Created session directory for comparison: {}", sessionPath);

            List<Path> s1StoredFilePaths = new ArrayList<>(); // Store paths of successfully stored files
            for (MultipartFile file : source1Files) {
                s1StoredFilePaths.add(storageService.storeUploadedFile(file, sessionPath));
            }
            logger.info("Stored {} files for Source 1 in session directory.", s1StoredFilePaths.size());

            List<Path> s2StoredFilePaths = new ArrayList<>();
            for (MultipartFile file : source2Files) {
                s2StoredFilePaths.add(storageService.storeUploadedFile(file, sessionPath));
            }
            logger.info("Stored {} files for Source 2 in session directory.", s2StoredFilePaths.size());

            // Pass s1StoredFilePaths and s2StoredFilePaths to compareService
            ComparisonResponse response = compareService.compareFiles(s1StoredFilePaths, s2StoredFilePaths, sortFiles, sessionPath, manualPairs);

            long executionTimeMs = System.currentTimeMillis() - startTime;

            relativeSessionPathForZip = storageService.getRelativePathForClient(sessionPath, storageService.getBaseStoragePath());
            String potentialZipPath = (relativeSessionPathForZip != null ? relativeSessionPathForZip : sessionPath.getFileName().toString())
                    + "/comparison_reports_" + sessionPath.getFileName().toString() + ".zip";

            // Pass the lists of stored file Paths to the logging service
            loggingService.logComparison(response, sessionPath.getFileName().toString(), executionTimeMs, potentialZipPath, httpRequest, s1StoredFilePaths, s2StoredFilePaths);
            logger.info("Comparison completed successfully in {} ms. Session: {}", executionTimeMs, sessionPath.getFileName());

            httpSession.setAttribute(LAST_COMPARISON_SESSION_PATH_KEY, sessionPath.toString());
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            logger.error("IOException during file storage or comparison process: {}", e.getMessage(), e);
            long executionTimeMs = System.currentTimeMillis() - startTime;
            // Attempt to log the error if possible, even with partial info
            OverallMetrics errorMetrics = new OverallMetrics();
            errorMetrics.setTotalFilesS1(source1Files.size());
            errorMetrics.setTotalFilesS2(source2Files.size());
            // Create a dummy/error response for logging
            ComparisonResponse errorResponse = new ComparisonResponse(errorMetrics, Collections.emptyList(), null);
            loggingService.logComparison(errorResponse,
                    (sessionPath != null ? sessionPath.getFileName().toString() : "ERROR_SESSION_" + UUID.randomUUID()),
                    executionTimeMs, "N/A_DUE_TO_ERROR", httpRequest,
                    Collections.emptyList(), Collections.emptyList()); // Pass empty lists for filenames on error

            if (sessionPath != null) {
                logger.info("Attempting to clean up session directory due to error: {}", sessionPath);
                storageService.deleteSessionDirectory(sessionPath);
            }
            return ResponseEntity.status(500).body(new ComparisonResponse(errorMetrics, Collections.emptyList(), "Error during processing: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected exception during comparison process: {}", e.getMessage(), e);
            long executionTimeMs = System.currentTimeMillis() - startTime;
            OverallMetrics errorMetrics = new OverallMetrics();
            ComparisonResponse errorResponse = new ComparisonResponse(errorMetrics, Collections.emptyList(), null);
            loggingService.logComparison(errorResponse, (sessionPath != null ? sessionPath.getFileName().toString() : "UNEXPECTED_ERROR_SESSION_" + UUID.randomUUID()),
                    executionTimeMs, "N/A_DUE_TO_UNEXPECTED_ERROR", httpRequest, Collections.emptyList(), Collections.emptyList());

            if (sessionPath != null) {
                storageService.deleteSessionDirectory(sessionPath);
            }
            return ResponseEntity.status(500).body(new ComparisonResponse(errorMetrics, Collections.emptyList(), "An unexpected error occurred: " + e.getMessage()));
        }
    }

    @GetMapping("/download-reports")
    public ResponseEntity<Resource> downloadReportsZip(HttpSession httpSession) {
        String sessionPathString = (String) httpSession.getAttribute(LAST_COMPARISON_SESSION_PATH_KEY);

        if (sessionPathString == null) {
            logger.warn("Attempt to download reports but no session path found in HTTP session. User might need to perform a comparison first.");
            return ResponseEntity.badRequest().body(null); // Or ResponseEntity.noContent().build();
        }

        Path sessionAbsolutePath = Paths.get(sessionPathString);
        if (!Files.exists(sessionAbsolutePath) || !Files.isDirectory(sessionAbsolutePath)) {
            logger.warn("Session path {} from HTTP session does not exist or is not a directory.", sessionPathString);
            return ResponseEntity.notFound().build();
        }

        // Construct the ZIP file name based on the session directory's name (which is a UUID)
        String zipFileName = "comparison_reports_" + sessionAbsolutePath.getFileName().toString() + ".zip";
        Path zipFilePath = null;

        try {
            // The createZipFromReports method will create the zip inside the session directory
            zipFilePath = storageService.createZipFromReports(sessionAbsolutePath, zipFileName);

            if (!Files.exists(zipFilePath)) {
                logger.error("ZIP file {} was expected but not found after creation attempt.", zipFilePath);
                return ResponseEntity.status(500).body(null); // Internal server error
            }

            Resource resource = new InputStreamResource(new FileInputStream(zipFilePath.toFile()));
            logger.info("Serving ZIP file: {}", zipFilePath);

            // Note: Automatic cleanup after download might be too aggressive.
            // The user might want to interact more with the results or download again.
            // Consider a separate explicit cleanup action or timed cleanup.
            // For now, we leave the cleanup to the /cleanup-comparison-session endpoint.

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipFilePath.getFileName().toString() + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(Files.size(zipFilePath))
                    .body(resource);
        } catch (IOException e) {
            logger.error("Error creating or serving ZIP file from session path {}: {}", sessionAbsolutePath, e.getMessage(), e);
            // If zipFilePath was created but an error occurred while serving, try to delete it.
            if (zipFilePath != null && Files.exists(zipFilePath)) {
                try { Files.delete(zipFilePath); } catch (IOException ex) { logger.error("Could not delete partially created/failed zip {}: {}", zipFilePath, ex.getMessage()); }
            }
            return ResponseEntity.status(500).body(null);
        }
    }

    @PostMapping("/cleanup-comparison-session")
    @ResponseBody
    public ResponseEntity<String> cleanupComparisonSession(HttpSession httpSession) {
        String sessionPathString = (String) httpSession.getAttribute(LAST_COMPARISON_SESSION_PATH_KEY);

        if (sessionPathString != null) {
            Path sessionAbsolutePath = Paths.get(sessionPathString);
            logger.info("User initiated cleanup for session: {}", sessionPathString);
            storageService.deleteSessionDirectory(sessionAbsolutePath); // This handles actual deletion
            httpSession.removeAttribute(LAST_COMPARISON_SESSION_PATH_KEY); // Clear from HTTP session
            return ResponseEntity.ok("Comparison session data and temporary files cleaned up successfully.");
        }
        logger.warn("Cleanup requested, but no active comparison session found in HTTP session.");
        return ResponseEntity.badRequest().body("No active comparison session found to clean up.");
    }
}