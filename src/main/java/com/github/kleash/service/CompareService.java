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

    public ComparisonResponse compareFiles(
            List<Path> source1FilePaths, List<Path> source2FilePaths,
            boolean sortFileNames, Path sessionPath, List<ManualPair> manualPairs,
            ColumnIgnoreConfig ignoreConfig, boolean s1IncludesHeaderInOutput, boolean s2IncludesHeaderInOutput) {

        List<Path> s1WorkList = new ArrayList<>(source1FilePaths);
        List<Path> s2WorkList = new ArrayList<>(source2FilePaths);

        if (sortFileNames) {
            Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString().toLowerCase());
            s1WorkList.sort(byName);
            s2WorkList.sort(byName);
            logger.info("Source file lists sorted by name.");
        }

        OverallMetrics metrics = new OverallMetrics();
        metrics.setTotalFilesS1(s1WorkList.size());
        metrics.setTotalFilesS2(s2WorkList.size());
        List<FilePairResult> pairResults = new ArrayList<>();

        Set<Path> s1ProcessedPaths = new HashSet<>(); // Tracks S1 paths processed by manual or auto pairing
        Set<Path> s2ProcessedPaths = new HashSet<>(); // Tracks S2 paths processed

        // 1. Process Manual Pairs
        if (manualPairs != null && !manualPairs.isEmpty()) {
            logger.info("Processing {} manual pairs.", manualPairs.size());
            for (ManualPair pair : manualPairs) {
                Path file1Path = findPathByFilename(s1WorkList, pair.getSource1FileName());
                Path file2Path = findPathByFilename(s2WorkList, pair.getSource2FileName());

                String f1NameForError = (file1Path != null) ? file1Path.getFileName().toString() : pair.getSource1FileName();
                String f2NameForError = (file2Path != null) ? file2Path.getFileName().toString() : pair.getSource2FileName();

                if (file1Path != null && file2Path != null &&
                        !s1ProcessedPaths.contains(file1Path) && !s2ProcessedPaths.contains(file2Path)) {
                    FilePairResult result = compareSinglePairAndSaveReport(file1Path, file2Path, sessionPath, ignoreConfig, s1IncludesHeaderInOutput, s2IncludesHeaderInOutput);
                    pairResults.add(result);
                    updateMetricsFromPairResult(metrics, result, false, false);
                    s1ProcessedPaths.add(file1Path);
                    s2ProcessedPaths.add(file2Path);
                } else {
                    logger.warn("Manual pair references non-existent file(s) or file already used: S1: {}, S2: {}", f1NameForError, f2NameForError);
                    FilePairResult errorResult = new FilePairResult();
                    errorResult.setSource1FileName(f1NameForError);
                    errorResult.setSource2FileName(f2NameForError);
                    errorResult.setStatus(FilePairResult.Status.PARSE_ERROR_S1); // Or a specific PAIRING_ERROR status
                    errorResult.setErrorMessage("File(s) in manual pair not found/uploaded or already processed in another manual pair.");
                    saveReportForPair(errorResult, sessionPath);
                    pairResults.add(errorResult);
                    if (file1Path != null) s1ProcessedPaths.add(file1Path); // Mark as processed to avoid re-processing
                    if (file2Path != null) s2ProcessedPaths.add(file2Path);
                }
            }
        }

        // 2. Automatic Pairing by Sorted Index (for files NOT manually paired)
        List<Path> s1AutoPairList = s1WorkList.stream().filter(p -> !s1ProcessedPaths.contains(p)).collect(Collectors.toList());
        List<Path> s2AutoPairList = s2WorkList.stream().filter(p -> !s2ProcessedPaths.contains(p)).collect(Collectors.toList());

        if (sortFileNames) { // Only perform index-based auto pairing if sorting was requested
            int autoPairCount = Math.min(s1AutoPairList.size(), s2AutoPairList.size());
            logger.info("Attempting automatic pairing by sorted index for up to {} pairs.", autoPairCount);
            for (int i = 0; i < autoPairCount; i++) {
                Path file1 = s1AutoPairList.get(i);
                Path file2 = s2AutoPairList.get(i);
                FilePairResult result = compareSinglePairAndSaveReport(file1, file2, sessionPath, ignoreConfig, s1IncludesHeaderInOutput, s2IncludesHeaderInOutput);
                pairResults.add(result);
                updateMetricsFromPairResult(metrics, result, false, false);
                s1ProcessedPaths.add(file1); // Mark as processed
                s2ProcessedPaths.add(file2);
            }
        } else {
            logger.info("Automatic pairing by index skipped as 'sortFileNames' is false.");
        }


        // 3. Identify Unpaired Files
        logger.info("Identifying unpaired files.");
        for (Path file1 : s1WorkList) {
            if (!s1ProcessedPaths.contains(file1)) {
                addMissingFileResult(file1, null, pairResults, metrics, sessionPath, false, ignoreConfig, s1IncludesHeaderInOutput);
                s1ProcessedPaths.add(file1); // Ensure it's marked processed
            }
        }
        for (Path file2 : s2WorkList) {
            if (!s2ProcessedPaths.contains(file2)) {
                addMissingFileResult(null, file2, pairResults, metrics, sessionPath, true, ignoreConfig, s2IncludesHeaderInOutput);
                s2ProcessedPaths.add(file2); // Ensure it's marked processed
            }
        }

        metrics.setPairsConsidered((int) pairResults.stream()
                .filter(r -> r.getStatus() != FilePairResult.Status.MISSING_IN_SOURCE1 &&
                        r.getStatus() != FilePairResult.Status.MISSING_IN_SOURCE2 &&
                        !(r.getErrorMessage() != null && r.getErrorMessage().contains("manual pair")))
                .count());

        String relativeSessionPath = storageService.getRelativePathForClient(sessionPath, storageService.getBaseStoragePath());
        return new ComparisonResponse(metrics, pairResults, relativeSessionPath);
    }

    private Path findPathByFilename(List<Path> paths, String filename) {
        if (filename == null || paths == null) return null;
        return paths.stream()
                .filter(p -> p.getFileName().toString().equals(filename))
                .findFirst().orElse(null);
    }

    private FilePairResult compareSinglePairAndSaveReport(Path file1Path, Path file2Path, Path sessionPath,
                                                          ColumnIgnoreConfig ignoreConfig, boolean s1IncludesHeaderInOutput, boolean s2IncludesHeaderInOutput) {
        FilePairResult result = new FilePairResult();
        result.setSource1FileName(file1Path.getFileName().toString());
        result.setSource2FileName(file2Path.getFileName().toString());
        result.setSource1FilePath(file1Path);
        result.setSource2FilePath(file2Path);

        FileParserService.ParsedFileResult parsedS1, parsedS2;
        List<String[]> originalRowsS1, originalRowsS2;
        String[] headerS1, headerS2;
        Set<Integer> ignoreIndicesS1 = new HashSet<>();
        Set<Integer> ignoreIndicesS2 = new HashSet<>();

        // --- Parsing and Ignore Index Calculation ---
        try {
            parsedS1 = fileParserService.parseFileIntoRowsAndHeader(file1Path);
            originalRowsS1 = new ArrayList<>(parsedS1.getRows());
            headerS1 = parsedS1.getHeader();
            Set<String> s1IgnoresFromConfig = (ignoreConfig != null && ignoreConfig.getSource1Ignore() != null) ? ignoreConfig.getSource1Ignore() : Collections.emptySet();
            if (headerS1 != null) {
                for (int i = 0; i < headerS1.length; i++) {
                    if (s1IgnoresFromConfig.contains(headerS1[i]) || s1IgnoresFromConfig.contains(String.valueOf(i))) {
                        ignoreIndicesS1.add(i);
                    }
                }
            } else {
                for(String idxStr : s1IgnoresFromConfig) { try { ignoreIndicesS1.add(Integer.parseInt(idxStr)); } catch (NumberFormatException ignored) {}}
            }
            result.setSource1Content(fileParserService.getProcessedLines(parsedS1, s1IgnoresFromConfig, s1IncludesHeaderInOutput));
        } catch (IOException | CsvException e) {
            logger.error("Error parsing Source 1 file {}: {}", file1Path.getFileName(), e.getMessage());
            result.setStatus(FilePairResult.Status.PARSE_ERROR_S1);
            result.setErrorMessage("Error parsing " + file1Path.getFileName() + ": " + e.getMessage());
            saveReportForPair(result, sessionPath); return result;
        }

        try {
            parsedS2 = fileParserService.parseFileIntoRowsAndHeader(file2Path);
            originalRowsS2 = new ArrayList<>(parsedS2.getRows());
            headerS2 = parsedS2.getHeader();
            Set<String> s2IgnoresFromConfig = (ignoreConfig != null && ignoreConfig.getSource2Ignore() != null) ? ignoreConfig.getSource2Ignore() : Collections.emptySet();
            if (headerS2 != null) {
                for (int i = 0; i < headerS2.length; i++) {
                    if (s2IgnoresFromConfig.contains(headerS2[i]) || s2IgnoresFromConfig.contains(String.valueOf(i))) {
                        ignoreIndicesS2.add(i);
                    }
                }
            } else {
                for(String idxStr : s2IgnoresFromConfig) { try { ignoreIndicesS2.add(Integer.parseInt(idxStr)); } catch (NumberFormatException ignored) {}}
            }
            result.setSource2Content(fileParserService.getProcessedLines(parsedS2, s2IgnoresFromConfig, s2IncludesHeaderInOutput));
        } catch (IOException | CsvException e) {
            logger.error("Error parsing Source 2 file {}: {}", file2Path.getFileName(), e.getMessage());
            result.setStatus(FilePairResult.Status.PARSE_ERROR_S2);
            result.setErrorMessage("Error parsing " + file2Path.getFileName() + ": " + e.getMessage());
            saveReportForPair(result, sessionPath); return result;
        }

        logger.debug("File1: {}, S1 Ignore Indices: {}", file1Path.getFileName(), ignoreIndicesS1);
        logger.debug("File2: {}, S2 Ignore Indices: {}", file2Path.getFileName(), ignoreIndicesS2);

        int comparisonStartRowIndexS1 = 0;
        if (headerS1 != null && parsedS1.isFirstRowHeaderForDetection() && !s1IncludesHeaderInOutput && !originalRowsS1.isEmpty()) {
            comparisonStartRowIndexS1 = 1;
        }
        int comparisonStartRowIndexS2 = 0;
        if (headerS2 != null && parsedS2.isFirstRowHeaderForDetection() && !s2IncludesHeaderInOutput && !originalRowsS2.isEmpty()) {
            comparisonStartRowIndexS2 = 1;
        }

        int effectiveLenS1 = originalRowsS1.size() - comparisonStartRowIndexS1;
        int effectiveLenS2 = originalRowsS2.size() - comparisonStartRowIndexS2;
        int maxEffectiveLen = Math.max(effectiveLenS1, effectiveLenS2);

        boolean isOverallContentMismatch = false; // Tracks if any content mismatch occurs after ignoring columns
        int finalLineMatchCount = 0;
        int finalLineContentMismatchCount = 0;
        int finalLinesMissingInS1Data = 0;
        int finalLinesMissingInS2Data = 0;

        for (int i = 0; i < maxEffectiveLen; i++) {
            String[] originalRow1DataArray = (i < effectiveLenS1) ? originalRowsS1.get(comparisonStartRowIndexS1 + i) : null;
            String[] originalRow2DataArray = (i < effectiveLenS2) ? originalRowsS2.get(comparisonStartRowIndexS2 + i) : null;

            String reportLineS1 = getReportableLine(result.getSource1Content(), i, s1IncludesHeaderInOutput, headerS1 != null && parsedS1.isFirstRowHeaderForDetection());
            String reportLineS2 = getReportableLine(result.getSource2Content(), i, s2IncludesHeaderInOutput, headerS2 != null && parsedS2.isFirstRowHeaderForDetection());

            int currentDataLineNumber = i + 1;

            if (originalRow1DataArray != null && originalRow2DataArray != null) {
                boolean currentRowColumnsMatch = true;

                List<String> r1KeptValues = new ArrayList<>();
                for (int c = 0; c < originalRow1DataArray.length; c++) {
                    if (!ignoreIndicesS1.contains(c)) r1KeptValues.add(originalRow1DataArray[c] == null ? "" : originalRow1DataArray[c].trim());
                }
                List<String> r2KeptValues = new ArrayList<>();
                for (int c = 0; c < originalRow2DataArray.length; c++) {
                    if (!ignoreIndicesS2.contains(c)) r2KeptValues.add(originalRow2DataArray[c] == null ? "" : originalRow2DataArray[c].trim());
                }

                if (r1KeptValues.size() != r2KeptValues.size()) {
                    currentRowColumnsMatch = false;
                    logger.trace("L{}: Kept column count differs. S1_kept: {} ({}), S2_kept: {} ({})", currentDataLineNumber, r1KeptValues.size(), r1KeptValues, r2KeptValues.size(), r2KeptValues);
                } else {
                    for (int k = 0; k < r1KeptValues.size(); k++) {
                        // Objects.equals already handles nulls correctly. Trimming is done above.
                        if (!r1KeptValues.get(k).equals(r2KeptValues.get(k))) {
                            currentRowColumnsMatch = false;
                            logger.trace("L{}: Kept column content differs at effective index {}: S1='{}', S2='{}'", currentDataLineNumber, k, r1KeptValues.get(k), r2KeptValues.get(k));
                            break;
                        }
                    }
                }

                if (!currentRowColumnsMatch) {
                    result.getDifferences().add(new LineDifference(currentDataLineNumber, reportLineS1, reportLineS2, LineDifference.DiffType.MISMATCH));
                    finalLineContentMismatchCount++;
                    isOverallContentMismatch = true;
                } else {
                    finalLineMatchCount++;
                    // This line matches after ignoring columns. CsvReportGenerator will reflect this.
                }
            } else if (originalRow1DataArray != null) { // originalRow2Data is null -> missing in S2 data
                result.getDifferences().add(new LineDifference(currentDataLineNumber, reportLineS1, null, LineDifference.DiffType.MISSING_IN_SOURCE2));
                finalLinesMissingInS2Data++;
                isOverallContentMismatch = true;
            } else if (originalRow2DataArray != null) { // originalRow1Data is null -> missing in S1 data
                result.getDifferences().add(new LineDifference(currentDataLineNumber, null, reportLineS2, LineDifference.DiffType.MISSING_IN_SOURCE1));
                finalLinesMissingInS1Data++;
                isOverallContentMismatch = true;
            }
        }

        result.setMatchCount(finalLineMatchCount);
        result.setMismatchCount(finalLineContentMismatchCount);
        result.setMissingInSource1Count(finalLinesMissingInS1Data);
        result.setMissingInSource2Count(finalLinesMissingInS2Data);

        // Final status determination
        if (effectiveLenS1 != effectiveLenS2) {
            isOverallContentMismatch = true; // Different number of data rows is a mismatch
        }

        if (isOverallContentMismatch) {
            result.setStatus(FilePairResult.Status.MISMATCHED);
        } else {
            result.setStatus(FilePairResult.Status.MATCHED); // Only if no content diffs AND no length diffs AND no missing lines
        }

        // Sanity check for MATCHED status
        if (result.getStatus() == FilePairResult.Status.MATCHED &&
                (result.getMismatchCount() > 0 || result.getMissingInSource1Count() > 0 || result.getMissingInSource2Count() > 0 || effectiveLenS1 != effectiveLenS2)) {
            logger.warn("Correcting status: Marked MATCHED but had diffs/length issues. File1: {}, File2: {}", result.getSource1FileName(), result.getSource2FileName());
            result.setStatus(FilePairResult.Status.MISMATCHED);
        }

        logger.info("Comparison for {} vs {}: Status={}, Matches={}, ContentMismatches={}, LinesOnlyInS1={}, LinesOnlyInS2={}",
                result.getSource1FileName(), result.getSource2FileName(), result.getStatus(),
                result.getMatchCount(), result.getMismatchCount(), result.getMissingInSource1Count(), result.getMissingInSource2Count());

        saveReportForPair(result, sessionPath);
        return result;
    }

    private String getReportableLine(List<String> processedContent, int dataRowIndex, boolean includesHeaderInOutput, boolean wasHeaderDetected) {
        if (processedContent == null) return null;

        int reportIndex = dataRowIndex; // Default: 0th data row is 0th in processedContent

        if (includesHeaderInOutput && wasHeaderDetected) {
            // If header is the 0th element of processedContent, then 0th data row is at index 1
            reportIndex = dataRowIndex + 1;
        }
        // If header is NOT in processedContent (includesHeaderInOutput=false, but wasHeaderDetected=true),
        // then 0th data row is at index 0 of processedContent. So, `reportIndex = dataRowIndex` is correct.

        // If no header was detected at all, `includesHeaderInOutput` doesn't change indexing from dataRowIndex.
        // This means if `wasHeaderDetected` is false, `includesHeaderInOutput` effectively has no bearing on this specific index calculation.

        if (reportIndex < processedContent.size()) {
            return processedContent.get(reportIndex);
        }
        // This case implies that originalRows had more data lines than processedContent,
        // which could happen if parsing or getProcessedLines had an issue.
        // Or, if the logic for header skipping vs. `includesHeaderInOutput` is misaligned.
        // For safety, return null if index is out of bounds.
        logger.warn("getReportableLine: reportIndex {} out of bounds for processedContent size {}. dataRowIndex: {}, includesHeader: {}, wasHeader: {}",
                reportIndex, processedContent.size(), dataRowIndex, includesHeaderInOutput, wasHeaderDetected);
        return null;
    }

    private void addMissingFileResult(Path file1Path, Path file2Path, List<FilePairResult> pairResults,
                                      OverallMetrics metrics, Path sessionPath, boolean isMissingInS1,
                                      ColumnIgnoreConfig ignoreConfig, boolean fileIncludesHeaderInOutput) {
        FilePairResult missingResult = new FilePairResult();
        Path presentFilePath = isMissingInS1 ? file2Path : file1Path;
        Set<String> ignores = Collections.emptySet();

        if (isMissingInS1) {
            missingResult.setSource2FileName(presentFilePath.getFileName().toString());
            missingResult.setSource2FilePath(presentFilePath);
            missingResult.setStatus(FilePairResult.Status.MISSING_IN_SOURCE1);
            if (ignoreConfig != null && ignoreConfig.getSource2Ignore() != null) ignores = ignoreConfig.getSource2Ignore();
        } else {
            missingResult.setSource1FileName(presentFilePath.getFileName().toString());
            missingResult.setSource1FilePath(presentFilePath);
            missingResult.setStatus(FilePairResult.Status.MISSING_IN_SOURCE2);
            if (ignoreConfig != null && ignoreConfig.getSource1Ignore() != null) ignores = ignoreConfig.getSource1Ignore();
        }

        try {
            FileParserService.ParsedFileResult parsedFile = fileParserService.parseFileIntoRowsAndHeader(presentFilePath);
            List<String> content = fileParserService.getProcessedLines(parsedFile, ignores, fileIncludesHeaderInOutput);
            if (isMissingInS1) missingResult.setSource2Content(content);
            else missingResult.setSource1Content(content);
        } catch (Exception e) {
            logger.error("Error parsing supposedly present file {} for 'missing' result: {}", presentFilePath.getFileName(), e.getMessage());
            missingResult.setErrorMessage("Error parsing file " + presentFilePath.getFileName() + ": " + e.getMessage());
        }

        saveReportForPair(missingResult, sessionPath);
        pairResults.add(missingResult);
        updateMetricsFromPairResult(metrics, missingResult, isMissingInS1, !isMissingInS1);
    }

    private void saveReportForPair(FilePairResult result, Path sessionPath) {
        String csvContent = CsvReportGenerator.generateCsvContent(result);
        String s1NamePart = result.getSource1FileName() != null ? result.getSource1FileName().replaceAll("[^a-zA-Z0-9.\\-_]", "_") : "s1_unknown";
        String s2NamePart = result.getSource2FileName() != null ? result.getSource2FileName().replaceAll("[^a-zA-Z0-9.\\-_]", "_") : "s2_unknown";

        if ("s1_unknown".equals(s1NamePart) && result.getSource2FileName() != null) s1NamePart = "missing_S1_for_" + s2NamePart;
        else if ("s2_unknown".equals(s2NamePart) && result.getSource1FileName() != null) s2NamePart = "missing_S2_for_" + s1NamePart;
        if (s1NamePart.startsWith("missing_S1_for_") && s2NamePart.startsWith("missing_S2_for_")) s2NamePart = "file";

        String reportFileName = "report_" + s1NamePart + "_vs_" + s2NamePart + ".csv";

        try {
            Path reportPath = storageService.storeReport(csvContent, reportFileName, sessionPath);
            result.setIndividualReportPath(storageService.getRelativePathForClient(reportPath, sessionPath));
        } catch (IOException e) {
            logger.error("Could not save individual report for pair {} vs {}: {}", result.getSource1FileName(), result.getSource2FileName(), e.getMessage());
            result.setErrorMessage((result.getErrorMessage() == null ? "" : result.getErrorMessage()) + " | Could not save report file.");
        }
    }

    private void updateMetricsFromPairResult(OverallMetrics metrics, FilePairResult result, boolean onlyInS1, boolean onlyInS2) {
        if (onlyInS1) { metrics.setFilesOnlyInSource1(metrics.getFilesOnlyInSource1() + 1); return; }
        if (onlyInS2) { metrics.setFilesOnlyInSource2(metrics.getFilesOnlyInSource2() + 1); return; }

        if (result.getErrorMessage() != null && result.getErrorMessage().contains("manual pair")) return;

        if (result.getStatus() == FilePairResult.Status.MATCHED) metrics.setFullyMatchedPairs(metrics.getFullyMatchedPairs() + 1);
        else if (result.getStatus() == FilePairResult.Status.MISMATCHED || result.getStatus() == FilePairResult.Status.DIFFERENT_ROW_COUNT) metrics.setMismatchedPairs(metrics.getMismatchedPairs() + 1);

        metrics.setTotalLineMatches(metrics.getTotalLineMatches() + result.getMatchCount());
        metrics.setTotalLineMismatches(metrics.getTotalLineMismatches() + result.getMismatchCount());
        metrics.setTotalLinesMissingInS1(metrics.getTotalLinesMissingInS1() + result.getMissingInSource1Count());
        metrics.setTotalLinesMissingInS2(metrics.getTotalLinesMissingInS2() + result.getMissingInSource2Count());
    }
}