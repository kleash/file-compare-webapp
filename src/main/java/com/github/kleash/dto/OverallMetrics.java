package com.github.kleash.dto;

import lombok.Data;

@Data
public class OverallMetrics {
    private int totalFilesS1;
    private int totalFilesS2;
    private int pairsConsidered;
    private int fullyMatchedPairs;
    private int mismatchedPairs;
    private int filesOnlyInSource1; // Unpaired
    private int filesOnlyInSource2; // Unpaired
    private int totalLineMatches;
    private int totalLineMismatches;
    private int totalLinesMissingInS1; // Across all compared pairs
    private int totalLinesMissingInS2; // Across all compared pairs
}