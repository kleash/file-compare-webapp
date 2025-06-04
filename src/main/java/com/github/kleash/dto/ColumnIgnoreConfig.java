package com.github.kleash.dto;
import lombok.Data;

import java.util.Set;

@Data
public class ColumnIgnoreConfig {
    // Using Set to avoid duplicates and for efficient lookup if needed
    // Stores header names or 0-based column indices as strings (e.g., "ProductID", "0", "2")
    private Set<String> source1Ignore;
    private Set<String> source2Ignore;
}
