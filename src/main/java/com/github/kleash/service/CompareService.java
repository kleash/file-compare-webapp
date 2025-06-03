package com.github.kleash.service;

import com.github.kleash.dto.*;
import com.opencsv.exceptions.CsvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CompareService {
    private static final Logger logger = LoggerFactory.getLogger(CompareService.class);

    @Autowired
    private FileParserService fileParserService;
    @Autowired
    private StorageService storageService;

    public ComparisonResponse compareFiles(List<Path> source1FilePaths, List<Path> source2FilePaths,
                                           boolean sortFileNames, Path sessionPath, List<ManualPair> manualPairs) {

        List<Path> s1Paths = new ArrayList<>(source1FilePaths);
        List<Path> s2Paths = new ArrayList<>(source2FilePaths);

        if (sortFileNames && (manualPairs == null || manualPairs.isEmpty())) {
            Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString());
            s1Paths.sort(byName);
            s2Paths.sort(byName);
        }

        OverallMetrics metrics = new OverallMetrics();
        metrics.setTotalFilesS1(s1Paths.size());
        metrics.setTotalFilesS2(s2Paths.size());
        List<FilePairResult> pairResults = new ArrayList<>();
        Set<String> s1ProcessedNames = new HashSet<>(); // Track S1 files processed to avoid double-counting if manual + auto
        Set<String> s2ProcessedNames = new HashSet<>(); // Track S2 files processed

        // 1. Process Manual Pairs
        if (manualPairs != null && !manualPairs.isEmpty()) {
            logger.info("Processing {} manual pairs.", manualPairs.size());
            for (ManualPair pair : manualPairs) {
                Path file1Path = findPathByFilename(s1Paths, pair.getSource1FileName());
                Path file2Path = findPathByFilename(s2Paths, pair.getSource2FileName());

                if (file1Path != null && file2Path != null) {
                    FilePairResult result = compareSinglePairAndSaveReport(file1Path, file2Path, sessionPath);
                    pairResults.add(result);
                    updateMetricsFromPairResult(metrics, result, false, false);
                    s1ProcessedNames.add(file1Path.getFileName().toString());
                    s2ProcessedNames.add(file2Path.getFileName().toString());
                } else {
                    logger.warn("Manual pair references non-existent file(s): S1: {}, S2: {}", pair.getSource1FileName(), pair.getSource2FileName());
                    // Create a dummy result for this failed manual pair attempt
                    FilePairResult errorResult = new FilePairResult();
                    errorResult.setSource1FileName(pair.getSource1FileName());
                    errorResult.setSource2FileName(pair.getSource2FileName());
                    errorResult.setStatus(FilePairResult.Status.PARSE_ERROR_S1); // Or a new status like PAIRING_ERROR
                    errorResult.setErrorMessage("One or both files in manual pair not found/uploaded.");
                    saveReportForPair(errorResult, sessionPath); // Save error report
                    pairResults.add(errorResult);
                }
            }
        }

        // 2. Process Automatic Pairs (Filename Match or 1-to-1) for UNPROCESSED files
        if (s1Paths.size() == 1 && s2Paths.size() == 1 &&
                !s1ProcessedNames.contains(s1Paths.get(0).getFileName().toString()) &&
                !s2ProcessedNames.contains(s2Paths.get(0).getFileName().toString())) {
            // Single file comparison (if not already manually paired)
            Path file1 = s1Paths.get(0);
            Path file2 = s2Paths.get(0);
            FilePairResult result = compareSinglePairAndSaveReport(file1, file2, sessionPath);
            pairResults.add(result);
            updateMetricsFromPairResult(metrics, result, false, false);
            s1ProcessedNames.add(file1.getFileName().toString());
            s2ProcessedNames.add(file2.getFileName().toString());
        } else { // Multiple file automatic matching for UNPROCESSED files
            Map<String, Path> s2Map = s2Paths.stream()
                    .filter(p -> p.getFileName() != null && !s2ProcessedNames.contains(p.getFileName().toString())) // Only UNPROCESSED S2 files
                    .collect(Collectors.toMap(p -> p.getFileName().toString(), Function.identity(), (p1, p2) -> p1));

            for (Path file1 : s1Paths) {
                if (file1 == null || file1.getFileName() == null || s1ProcessedNames.contains(file1.getFileName().toString())) {
                    continue; // Already processed or invalid
                }
                String f1Name = file1.getFileName().toString();
                Path file2 = s2Map.get(f1Name);

                if (file2 != null) { // Found a match in UNPROCESSED S2 files
                    FilePairResult result = compareSinglePairAndSaveReport(file1, file2, sessionPath);
                    pairResults.add(result);
                    updateMetricsFromPairResult(metrics, result, false, false);
                    s1ProcessedNames.add(f1Name);
                    s2ProcessedNames.add(f1Name); // Mark S2 file as processed by its name
                }
            }
        }

        // 3. Identify Unpaired Files from S1
        for (Path file1 : s1Paths) {
            if (!s1ProcessedNames.contains(file1.getFileName().toString())) {
                addMissingFileResult(file1, null, pairResults, metrics, sessionPath, false); // Missing in S2
            }
        }

        // 4. Identify Unpaired Files from S2
        for (Path file2 : s2Paths) {
            if (!s2ProcessedNames.contains(file2.getFileName().toString())) {
                addMissingFileResult(null, file2, pairResults, metrics, sessionPath, true); // Missing in S1
            }
        }

        metrics.setPairsConsidered((int) pairResults.stream()
                .filter(r -> r.getStatus() != FilePairResult.Status.MISSING_IN_SOURCE1 &&
                        r.getStatus() != FilePairResult.Status.MISSING_IN_SOURCE2 &&
                        !(r.getErrorMessage() != null && r.getErrorMessage().contains("manual pair not found"))) // Exclude pairing errors
                .count());

        String relativeSessionPath = storageService.getRelativePathForClient(sessionPath, storageService.getBaseStoragePath());
        return new ComparisonResponse(metrics, pairResults, relativeSessionPath);
    }

    private Path findPathByFilename(List<Path> paths, String filename) {
        if (filename == null) return null;
        return paths.stream()
                .filter(p -> p.getFileName().toString().equals(filename))
                .findFirst().orElse(null);
    }

    private void addMissingFileResult(Path file1Path, Path file2Path, List<FilePairResult> pairResults,
                                      OverallMetrics metrics, Path sessionPath, boolean isMissingInS1) {
        FilePairResult missingResult = new FilePairResult();
        Path presentFilePath = isMissingInS1 ? file2Path : file1Path;

        if (isMissingInS1) {
            missingResult.setSource2FileName(presentFilePath.getFileName().toString());
            missingResult.setSource2FilePath(presentFilePath);
            missingResult.setStatus(FilePairResult.Status.MISSING_IN_SOURCE1);
        } else {
            missingResult.setSource1FileName(presentFilePath.getFileName().toString());
            missingResult.setSource1FilePath(presentFilePath);
            missingResult.setStatus(FilePairResult.Status.MISSING_IN_SOURCE2);
        }

        try {
            List<String> content = fileParserService.parseFile(presentFilePath);
            if (isMissingInS1) missingResult.setSource2Content(content);
            else missingResult.setSource1Content(content);
        } catch (Exception e) {
            logger.error("Error parsing supposedly present file {}: {}", presentFilePath.getFileName(), e.getMessage());
            missingResult.setErrorMessage("Error parsing file " + presentFilePath.getFileName() + ": " + e.getMessage());
        }

        saveReportForPair(missingResult, sessionPath);
        pairResults.add(missingResult);
        updateMetricsFromPairResult(metrics, missingResult, isMissingInS1, !isMissingInS1);
    }

    private FilePairResult compareSinglePairAndSaveReport(Path file1Path, Path file2Path, Path sessionPath) {
        FilePairResult result = new FilePairResult();
        result.setSource1FileName(file1Path.getFileName().toString());
        result.setSource2FileName(file2Path.getFileName().toString());
        result.setSource1FilePath(file1Path); // Store server path
        result.setSource2FilePath(file2Path); // Store server path

        List<String> lines1, lines2;
        try {
            lines1 = fileParserService.parseFile(file1Path);
            result.setSource1Content(new ArrayList<>(lines1));
        } catch (IOException | CsvException e) {
            result.setStatus(FilePairResult.Status.PARSE_ERROR_S1);
            result.setErrorMessage("Error parsing " + file1Path.getFileName() + ": " + e.getMessage());
            saveReportForPair(result, sessionPath);
            return result;
        }
        try {
            lines2 = fileParserService.parseFile(file2Path);
            result.setSource2Content(new ArrayList<>(lines2));
        } catch (IOException | CsvException e) {
            result.setStatus(FilePairResult.Status.PARSE_ERROR_S2);
            result.setErrorMessage("Error parsing " + file2Path.getFileName() + ": " + e.getMessage());
            saveReportForPair(result, sessionPath);
            return result;
        }

        // --- Line-by-line comparison logic ---
        int len1 = lines1.size();
        int len2 = lines2.size();
        int maxLen = Math.max(len1, len2);
        boolean mismatchFound = false;

        for (int i = 0; i < maxLen; i++) {
            String line1 = (i < len1) ? lines1.get(i) : null;
            String line2 = (i < len2) ? lines2.get(i) : null;

            if (line1 != null && line2 != null) {
                if (line1.equals(line2)) {
                    result.setMatchCount(result.getMatchCount() + 1);
                } else {
                    LineDifference diff = new LineDifference(i + 1, line1, line2, LineDifference.DiffType.MISMATCH);
                    result.getDifferences().add(diff);
                    result.setMismatchCount(result.getMismatchCount() + 1);
                    mismatchFound = true;
                }
            } else if (line1 != null) { // line2 is null, missing in source 2
                LineDifference diff = new LineDifference(i + 1, line1, null, LineDifference.DiffType.MISSING_IN_SOURCE2);
                result.getDifferences().add(diff);
                result.setMissingInSource2Count(result.getMissingInSource2Count() + 1);
                mismatchFound = true;
            } else { // line1 is null, missing in source 1 (line2 != null)
                LineDifference diff = new LineDifference(i + 1, null, line2, LineDifference.DiffType.MISSING_IN_SOURCE1);
                result.getDifferences().add(diff);
                result.setMissingInSource1Count(result.getMissingInSource1Count() + 1);
                mismatchFound = true;
            }
        }

        if (mismatchFound) {
            if (len1 != len2 && result.getMismatchCount() == 0 && result.getMissingInSource1Count() == 0 && result.getMissingInSource2Count() == 0) { // This condition might be tricky
                // This case is generally covered if len1 != len2 and mismatchFound = true due to missing lines
                result.setStatus(FilePairResult.Status.DIFFERENT_ROW_COUNT);
            } else {
                result.setStatus(FilePairResult.Status.MISMATCHED);
            }
        } else { // No mismatches found
            if (len1 != len2) { // Different lengths but all common lines matched
                result.setStatus(FilePairResult.Status.DIFFERENT_ROW_COUNT); // Still a difference
            } else {
                result.setStatus(FilePairResult.Status.MATCHED);
            }
        }
        // --- End of comparison logic ---

        saveReportForPair(result, sessionPath);
        return result;
    }

    private void saveReportForPair(FilePairResult result, Path sessionPath) {
        String csvContent = CsvReportGenerator.generateCsvContent(result);
        String s1NamePart = result.getSource1FileName() != null ? result.getSource1FileName().replaceAll("[^a-zA-Z0-9.\\-_]", "_") : "s1_unknown";
        String s2NamePart = result.getSource2FileName() != null ? result.getSource2FileName().replaceAll("[^a-zA-Z0-9.\\-_]", "_") : "s2_unknown";
        String reportFileName = "report_" + s1NamePart + "_vs_" + s2NamePart + ".csv";

        try {
            Path reportPath = storageService.storeReport(csvContent, reportFileName, sessionPath);
            result.setIndividualReportPath(storageService.getRelativePathForClient(reportPath, sessionPath));
        } catch (IOException e) {
            logger.error("Could not save individual report for pair {} vs {}: {}",
                    result.getSource1FileName(), result.getSource2FileName(), e.getMessage());
            result.setErrorMessage((result.getErrorMessage() == null ? "" : result.getErrorMessage()) + " | Could not save report file.");
        }
    }

    private void updateMetricsFromPairResult(OverallMetrics metrics, FilePairResult result, boolean onlyInS1, boolean onlyInS2) {
        if (onlyInS1) {
            metrics.setFilesOnlyInSource1(metrics.getFilesOnlyInSource1() + 1);
            return;
        }
        if (onlyInS2) {
            metrics.setFilesOnlyInSource2(metrics.getFilesOnlyInSource2() + 1);
            return;
        }
        // Avoid counting pairing errors in certain metrics
        if (result.getErrorMessage() != null && result.getErrorMessage().contains("manual pair not found")) {
            // Potentially a different metric for pairing failures
            return;
        }

        if (result.getStatus() == FilePairResult.Status.MATCHED) {
            metrics.setFullyMatchedPairs(metrics.getFullyMatchedPairs() + 1);
        } else if (result.getStatus() == FilePairResult.Status.MISMATCHED ||
                result.getStatus() == FilePairResult.Status.DIFFERENT_ROW_COUNT) {
            metrics.setMismatchedPairs(metrics.getMismatchedPairs() + 1);
        }
        // Parse errors are not "matched" or "mismatched" in content terms
        else if (result.getStatus() == FilePairResult.Status.PARSE_ERROR_S1 || result.getStatus() == FilePairResult.Status.PARSE_ERROR_S2) {
            // Potentially a metric for parse errors
        }

        metrics.setTotalLineMatches(metrics.getTotalLineMatches() + result.getMatchCount());
        metrics.setTotalLineMismatches(metrics.getTotalLineMismatches() + result.getMismatchCount());
        metrics.setTotalLinesMissingInS1(metrics.getTotalLinesMissingInS1() + result.getMissingInSource1Count());
        metrics.setTotalLinesMissingInS2(metrics.getTotalLinesMissingInS2() + result.getMissingInSource2Count());
    }
}