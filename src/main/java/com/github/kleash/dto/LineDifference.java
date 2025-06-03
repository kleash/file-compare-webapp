package com.github.kleash.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LineDifference {
    public enum DiffType { MATCH, MISMATCH, MISSING_IN_SOURCE1, MISSING_IN_SOURCE2 }
    private int lineNumber; // Original line number
    private String source1Line;
    private String source2Line;
    private DiffType type;
}