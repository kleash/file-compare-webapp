package com.github.kleash.service;

import com.github.kleash.dto.FilePairResult;
import com.github.kleash.dto.LineDifference;
import com.opencsv.CSVWriter; // Ensure this is imported
import java.io.StringWriter;
import java.util.List;

public class CsvReportGenerator {

    public static String generateCsvContent(FilePairResult pairResult) {
        StringWriter stringWriter = new StringWriter();
        try (CSVWriter writer = new CSVWriter(stringWriter)) {
            writer.writeNext(new String[]{
                    "Source 1 File", "Source 2 File", "Line Comparison Status", // Changed column header
                    "Data Source Context", "Data Content"
            });

            String originalS1Name = pairResult.getSource1FileName() != null ? pairResult.getSource1FileName() : "N/A";
            String originalS2Name = pairResult.getSource2FileName() != null ? pairResult.getSource2FileName() : "N/A";
            String overallFileStatus = pairResult.getStatus().toString(); // The overall status of the file pair

            // For the first line of this pair's report, write the full names and overall file status
            // For subsequent lines of the same pair, S1/S2 names can be blanked.
            String s1NameToWrite = originalS1Name;
            String s2NameToWrite = originalS2Name;
            String lineStatusToWrite; // This will hold the status for the current line

            if (pairResult.getStatus() == FilePairResult.Status.MATCHED) {
                lineStatusToWrite = "MATCHED"; // Overall and line status
                writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, overallFileStatus, "Overall", "Files are identical."});
                s1NameToWrite = "";
                s2NameToWrite = "";

                List<String> content = pairResult.getSource1Content();
                if (content != null && !content.isEmpty()) {
                    for (int i = 0; i < content.size(); i++) {
                        writer.writeNext(new String[]{
                                s1NameToWrite, s2NameToWrite, lineStatusToWrite, // Use specific line status
                                "Line " + (i + 1) + " - Content", // Context indicates matched
                                content.get(i)
                        });
                    }
                }
            } else if (pairResult.getStatus() == FilePairResult.Status.MISSING_IN_SOURCE1) {
                writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, overallFileStatus, "File Level", "Source 1 File Missing"});
                s1NameToWrite = ""; s2NameToWrite = "";
                if (pairResult.getSource2Content() != null && !pairResult.getSource2Content().isEmpty()) {
                    for (int i = 0; i < pairResult.getSource2Content().size(); i++) {
                        writer.writeNext(new String[]{
                                s1NameToWrite, s2NameToWrite, "PRESENT_IN_S2_ONLY", // Line specific status
                                "S2 Line " + (i + 1),
                                pairResult.getSource2Content().get(i)
                        });
                    }
                }
            } else if (pairResult.getStatus() == FilePairResult.Status.MISSING_IN_SOURCE2) {
                writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, overallFileStatus, "File Level", "Source 2 File Missing"});
                s1NameToWrite = ""; s2NameToWrite = "";
                if (pairResult.getSource1Content() != null && !pairResult.getSource1Content().isEmpty()) {
                    for (int i = 0; i < pairResult.getSource1Content().size(); i++) {
                        writer.writeNext(new String[]{
                                s1NameToWrite, s2NameToWrite, "PRESENT_IN_S1_ONLY", // Line specific status
                                "S1 Line " + (i + 1),
                                pairResult.getSource1Content().get(i)
                        });
                    }
                }
            } else if (pairResult.getStatus() == FilePairResult.Status.PARSE_ERROR_S1 || pairResult.getStatus() == FilePairResult.Status.PARSE_ERROR_S2) {
                writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, overallFileStatus, "Error", pairResult.getErrorMessage()});
            } else { // MISMATCHED or DIFFERENT_ROW_COUNT
                writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, overallFileStatus, "Summary", "File comparison shows differences."});
                s1NameToWrite = "";
                s2NameToWrite = "";

                int maxLen = Math.max(
                        (pairResult.getSource1Content() != null ? pairResult.getSource1Content().size() : 0),
                        (pairResult.getSource2Content() != null ? pairResult.getSource2Content().size() : 0)
                );

                for (int i = 0; i < maxLen; i++) {
                    String lineS1 = (pairResult.getSource1Content() != null && i < pairResult.getSource1Content().size()) ? pairResult.getSource1Content().get(i) : null;
                    String lineS2 = (pairResult.getSource2Content() != null && i < pairResult.getSource2Content().size()) ? pairResult.getSource2Content().get(i) : null;
                    String lineContext = "Line " + (i + 1);

                    if (lineS1 != null && lineS2 != null) {
                        if (lineS1.equals(lineS2)) {
                            lineStatusToWrite = "MATCHED_LINE";
                            writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, lineStatusToWrite, lineContext + " - Content", lineS1});
                        } else {
                            lineStatusToWrite = "MISMATCHED_LINE";
                            writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, lineStatusToWrite, lineContext + " - Source 1", lineS1});
                            // For the S2 part of the mismatch, we can keep the lineStatusToWrite or make it more specific
                            writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, lineStatusToWrite, lineContext + " - Source 2", lineS2});
                        }
                    } else if (lineS1 != null) { // Missing in S2
                        lineStatusToWrite = "MISSING_IN_S2_AT_LINE";
                        writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, lineStatusToWrite, lineContext + " - Source 1", lineS1});
                    } else if (lineS2 != null) { // Missing in S1
                        lineStatusToWrite = "MISSING_IN_S1_AT_LINE";
                        writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, lineStatusToWrite, lineContext + " - Source 2", lineS2});
                    }
                }
                if(maxLen == 0 && (pairResult.getStatus() == FilePairResult.Status.DIFFERENT_ROW_COUNT || pairResult.getStatus() == FilePairResult.Status.MISMATCHED)){
                    writer.writeNext(new String[]{s1NameToWrite, s2NameToWrite, overallFileStatus, "Content Info", "Files are different but no lines to display (e.g. one or both empty, or parse issue before content processing)."});
                }
            }
        } catch (Exception e) {
            System.err.println("Error generating CSV content for pair " +
                    (pairResult.getSource1FileName() != null ? pairResult.getSource1FileName() : "N/A") + "/" +
                    (pairResult.getSource2FileName() != null ? pairResult.getSource2FileName() : "N/A") + ": " + e.getMessage());
            // Append a clear error message to the string writer if something goes wrong
            stringWriter.append("Error generating CSV content for this file pair: ").append(e.getMessage());
        }
        return stringWriter.toString();
    }
}