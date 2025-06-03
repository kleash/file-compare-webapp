package com.github.kleash.dto;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class FilePairResult {
    public enum Status { MATCHED, MISMATCHED, MISSING_IN_SOURCE1, MISSING_IN_SOURCE2, PARSE_ERROR_S1, PARSE_ERROR_S2, DIFFERENT_ROW_COUNT}
    private String source1FileName;
    private String source2FileName;
    private transient java.nio.file.Path source1FilePath; // Server-side path, not for JSON response
    private transient java.nio.file.Path source2FilePath; // Server-side path, not for JSON response
    private Status status;
    private String errorMessage;
    private List<String> source1Content; // Parsed content
    private List<String> source2Content; // Parsed content
    private List<LineDifference> differences = new ArrayList<>();
    private int matchCount = 0;
    private int mismatchCount = 0;
    private int missingInSource1Count = 0;
    private int missingInSource2Count = 0;
    private String individualReportPath; // Relative path to the individual CSV report for this pair (for client)
}