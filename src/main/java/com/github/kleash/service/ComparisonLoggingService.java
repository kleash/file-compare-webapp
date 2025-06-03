package com.github.kleash.service;


import com.github.kleash.dto.ComparisonResponse;
import com.github.kleash.dto.OverallMetrics;
import com.github.kleash.model.ComparisonLog;
import com.github.kleash.repository.ComparisonLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest; // For User-Agent

@Service
public class ComparisonLoggingService {

    @Autowired
    private ComparisonLogRepository comparisonLogRepository;

    public void logComparison(ComparisonResponse response, String sessionId, long executionTimeMs, String reportsZipPath, HttpServletRequest request) {
        if (response == null || response.getMetrics() == null) {
            return; // Don't log if there's no valid response data
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
        log.setReportsZipPath(reportsZipPath); // Store relative path to ZIP

        if (request != null) {
            log.setUserAgent(request.getHeader("User-Agent"));
        }

        comparisonLogRepository.save(log);
    }
}