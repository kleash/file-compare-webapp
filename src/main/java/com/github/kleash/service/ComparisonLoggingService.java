package com.github.kleash.service;


import com.github.kleash.dto.ComparisonResponse;
import com.github.kleash.dto.OverallMetrics;
import com.github.kleash.model.ComparisonLog;
import com.github.kleash.repository.ComparisonLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ComparisonLoggingService {

    @Autowired
    private ComparisonLogRepository comparisonLogRepository;

    public void logComparison(
            ComparisonResponse response,
            String sessionId,
            long executionTimeMs,
            String reportsZipPath,
            HttpServletRequest request,
            List<Path> s1FilePaths, // Add original file paths
            List<Path> s2FilePaths  // Add original file paths
    ) {
        if (response == null || response.getMetrics() == null) {
            return;
        }
        OverallMetrics metrics = response.getMetrics();
        ComparisonLog log = new ComparisonLog();
        log.setSessionId(sessionId);
        log.setSource1FileCount(metrics.getTotalFilesS1());
        log.setSource2FileCount(metrics.getTotalFilesS2());
        log.setPairsConsidered(metrics.getPairsConsidered());
        log.setFullyMatchedPairs(metrics.getFullyMatchedPairs());
        log.setMismatchedPairs(metrics.getMismatchedPairs());
        log.setFilesOnlyInSource1(metrics.getFilesOnlyInSource1());
        log.setFilesOnlyInSource2(metrics.getFilesOnlyInSource2());
        log.setTotalExecutionTimeMs(executionTimeMs);
        log.setReportsZipPath(reportsZipPath);

        if (request != null) {
            log.setUserAgent(request.getHeader("User-Agent"));
        }

        // Set concatenated file names
        if (s1FilePaths != null) {
            log.setSource1FileNamesList(s1FilePaths.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList()));
        }
        if (s2FilePaths != null) {
            log.setSource2FileNamesList(s2FilePaths.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList()));
        }

        comparisonLogRepository.save(log);
    }
}