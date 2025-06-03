package com.github.kleash.model;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Data
public class ComparisonLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sessionId; // To link back to stored files if needed
    private LocalDateTime comparisonTimestamp;
    private int source1FileCount;
    private int source2FileCount;
    private int pairsConsidered;
    private int fullyMatchedPairs;
    private int mismatchedPairs;
    private int filesOnlyInSource1;
    private int filesOnlyInSource2;
    private long totalExecutionTimeMs; // Track performance
    private String userAgent; // Could be useful for usage tracking
    // You could add IP address, username if you have authentication, etc.
    private String reportsZipPath; // Relative path to the ZIP if generated

    public ComparisonLog() {
        this.comparisonTimestamp = LocalDateTime.now();
    }
}