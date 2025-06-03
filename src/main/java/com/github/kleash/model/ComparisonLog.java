package com.github.kleash.model;

import lombok.Data;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

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
    @Column(length = 1024) // Allow more space for concatenated filenames
    private String source1FileNames; // e.g., "fileA.txt, fileB.csv"

    @Column(length = 1024)
    private String source2FileNames;

    public ComparisonLog() {
        this.comparisonTimestamp = LocalDateTime.now();
    }

    // Helper methods to set filenames from a list of strings
    public void setSource1FileNamesList(List<String> names) {
        if (names == null || names.isEmpty()) {
            this.source1FileNames = null;
        } else {
            this.source1FileNames = String.join(", ", names);
        }
    }

    public void setSource2FileNamesList(List<String> names) {
        if (names == null || names.isEmpty()) {
            this.source2FileNames = null;
        } else {
            this.source2FileNames = String.join(", ", names);
        }
    }
}