package com.github.kleash.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComparisonResponse {
    private OverallMetrics metrics;
    private List<FilePairResult> pairResults;
    private String sessionDirectoryRelativePath; // Relative path to the session's storage (for client info)
}