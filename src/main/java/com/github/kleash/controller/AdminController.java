package com.github.kleash.controller;


import com.github.kleash.model.ComparisonLog;
import com.github.kleash.repository.ComparisonLogRepository;
import com.github.kleash.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable; // For downloading specific ZIP
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin") // Base path for admin pages
public class AdminController {

    @Autowired
    private ComparisonLogRepository comparisonLogRepository;

    @Autowired
    private StorageService storageService; // To resolve paths

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        // This will serve the admin_dashboard.html template
        return "admin_dashboard";
    }

    @GetMapping("/api/comparison-logs")
    @ResponseBody
    public List<ComparisonLog> getComparisonLogs() {
        return comparisonLogRepository.findAllByOrderByComparisonTimestampDesc();
    }

    @GetMapping("/api/usage-metrics")
    @ResponseBody
    public Map<String, Object> getUsageMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalComparisons", comparisonLogRepository.count());
        metrics.put("comparisonsLast24Hours", comparisonLogRepository.countByComparisonTimestampAfter(LocalDateTime.now().minusHours(24)));
        metrics.put("totalFilesProcessed", comparisonLogRepository.getTotalFilesCompared()); // Custom query
        metrics.put("totalMatchedPairsOverall", comparisonLogRepository.getTotalFullyMatchedPairsOverall());
        metrics.put("totalMismatchedPairsOverall", comparisonLogRepository.getTotalMismatchedPairsOverall());
        // Add more as needed
        return metrics;
    }

    @GetMapping("/download-session-zip/{sessionId}")
    public ResponseEntity<Resource> downloadSpecificSessionZip(@PathVariable String sessionId) {
        ComparisonLog log = comparisonLogRepository.findBySessionId(sessionId).stream().findFirst().orElse(null); // Assuming you add findBySessionId
        // Or, if reportsZipPath is consistently the relative path from base storage + session ID
        // String zipFileName = "comparison_reports_" + sessionId + ".zip"; // Construct as per your saving logic
        // Path sessionDir = storageService.getBaseStoragePath().resolve(sessionId);
        // Path zipFilePath = sessionDir.resolve(zipFileName);

        // A more robust way: find the log by session ID, then use its stored reportsZipPath
        // We need to add findBySessionId to ComparisonLogRepository or query more robustly
        // For now, let's assume log.getReportsZipPath() is the relative path FROM base storage
        if (log == null || log.getReportsZipPath() == null || log.getReportsZipPath().isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Construct full path to the zip. log.getReportsZipPath() might be like "session_id_xyz/reports.zip"
        Path zipFilePath = storageService.getBaseStoragePath().resolve(log.getReportsZipPath());


        if (!Files.exists(zipFilePath) || Files.isDirectory(zipFilePath)) {
            return ResponseEntity.notFound().build();
        }

        try {
            Resource resource = new InputStreamResource(new FileInputStream(zipFilePath.toFile()));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipFilePath.getFileName().toString() + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(Files.size(zipFilePath))
                    .body(resource);
        } catch (IOException e) {
            System.err.println("Error serving specific ZIP for session " + sessionId + ": " + e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }
}