package com.github.kleash.service;

import com.github.kleash.util.FileTypeUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files; // Added
import java.nio.file.Path;   // Added
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.opencsv.CSVReaderBuilder;
import java.util.HashSet;
import java.util.Set;

@Service
public class FileParserService {
    private static final Logger logger = LoggerFactory.getLogger(FileParserService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // New method signature for core parsing
    public ParsedFileResult parseFileIntoRowsAndHeader(Path filePath) throws IOException, CsvException {
        if (filePath == null || !Files.exists(filePath) || Files.isDirectory(filePath)) {
            logger.warn("Attempted to parse null, non-existent, or directory path: {}", filePath);
            return new ParsedFileResult(new ArrayList<>(), null);
        }

        FileTypeUtil.FileType type = FileTypeUtil.getFileType(filePath.getFileName().toString());
        List<String[]> rows = new ArrayList<>();
        String[] header = null;

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            switch (type) {
                case CSV:
                    try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).build()) {
                        List<String[]> allRows = reader.readAll();
                        if (allRows != null && !allRows.isEmpty()) {
                            header = allRows.get(0); // Assume first line is header for CSV
                            rows.addAll(allRows); // Include header in rows for now, filtering will handle it
                        }
                    }
                    break;
                case EXCEL_XLS:
                case EXCEL_XLSX:
                    Workbook workbook;
                    if (type == FileTypeUtil.FileType.EXCEL_XLSX) workbook = new XSSFWorkbook(inputStream);
                    else workbook = new HSSFWorkbook(inputStream);

                    DataFormatter dataFormatter = new DataFormatter();
                    Sheet sheet = workbook.getSheetAt(0); // Assuming first sheet
                    boolean firstRow = true;
                    for (Row row : sheet) {
                        List<String> cellValues = new ArrayList<>();
                        for (int cn = 0; cn < row.getLastCellNum(); cn++) { // Iterate up to last cell number
                            Cell cell = row.getCell(cn, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                            cellValues.add(cell != null ? dataFormatter.formatCellValue(cell) : "");
                        }
                        if (firstRow) {
                            header = cellValues.toArray(new String[0]);
                            firstRow = false;
                        }
                        rows.add(cellValues.toArray(new String[0]));
                    }
                    workbook.close();
                    break;
                case JSON:
                    // For JSON, we'll still treat each "line" after pretty printing as a row.
                    // Columnar ignore for complex JSON is very hard. For this implementation,
                    // we assume if JSON is used with column ignore, it's structured line-oriented JSON.
                    // Or, the feature is implicitly less effective for generic JSON.
                    List<String> jsonLines = parseJsonToList(inputStream, filePath);
                    if (!jsonLines.isEmpty()) {
                        // Try to parse the first line as a simple delimited string for header (heuristic)
                        header = jsonLines.get(0).split("[,;:\\t]"); // Common delimiters
                        for (String line : jsonLines) {
                            rows.add(new String[]{line}); // Each JSON line becomes a single-column row for now
                        }
                    }
                    break;
                case TEXT:
                    List<String> textLines = parseTextToList(inputStream);
                    if (!textLines.isEmpty()) {
                        // Try to parse the first line as a simple delimited string for header (heuristic)
                        header = textLines.get(0).split("[,;:\\t]"); // Common delimiters
                        for (String line : textLines) {
                            // Attempt to split text lines if a consistent delimiter is used,
                            // otherwise treat as single column. For simplicity, single column:
                            rows.add(new String[]{line});
                        }
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported file type for row parsing: " + filePath.getFileName().toString());
            }
        }
        return new ParsedFileResult(rows, header);
    }


    // New method to process rows and apply column ignoring
    public List<String> getProcessedLines(ParsedFileResult parsedFile, Set<String> columnsToIgnoreNamesOrIndices, boolean treatFirstRowAsHeaderInOutput) {
        List<String> processedLines = new ArrayList<>();
        if (parsedFile == null || parsedFile.getRows().isEmpty()) {
            return processedLines;
        }

        String[] header = parsedFile.getHeader();
        List<String[]> originalRows = parsedFile.getRows();
        Set<Integer> ignoreIndices = new HashSet<>();

        if (header != null && columnsToIgnoreNamesOrIndices != null && !columnsToIgnoreNamesOrIndices.isEmpty()) {
            for (int i = 0; i < header.length; i++) {
                if (columnsToIgnoreNamesOrIndices.contains(header[i]) || columnsToIgnoreNamesOrIndices.contains(String.valueOf(i))) {
                    ignoreIndices.add(i);
                }
            }
        } else if (columnsToIgnoreNamesOrIndices != null && !columnsToIgnoreNamesOrIndices.isEmpty()) {
            // No header, but ignore indices were provided
            for (String indexStr : columnsToIgnoreNamesOrIndices) {
                try {
                    ignoreIndices.add(Integer.parseInt(indexStr));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid column index to ignore (must be integer when no header): {}", indexStr);
                }
            }
        }

        logger.debug("Ignoring column indices: {}", ignoreIndices);

        int startRow = (treatFirstRowAsHeaderInOutput && header != null) ? 0 : 0;
        // If we used first row as header for index detection, and it should be INCLUDED in output rows, start from 0.
        // If it should be EXCLUDED (typical CSV scenario), start from 1.
        // Let's assume `treatFirstRowAsHeaderInOutput` means the first row of `originalRows` IS the header
        // and should be processed (and potentially included if not entirely ignored).
        // The client side will tell us this based on user choice.

        for (int rowIndex = 0; rowIndex < originalRows.size(); rowIndex++) {
            String[] originalRow = originalRows.get(rowIndex);
            if (treatFirstRowAsHeaderInOutput && rowIndex == 0 && header != null) {
                // Process the header row itself
                List<String> keptHeaderColumns = new ArrayList<>();
                for (int i = 0; i < originalRow.length; i++) {
                    if (!ignoreIndices.contains(i)) {
                        keptHeaderColumns.add(originalRow[i]);
                    }
                }
                if (!keptHeaderColumns.isEmpty()) { // Only add header if it has content after filtering
                    processedLines.add(String.join(",", keptHeaderColumns.toArray(new String[0])));
                }
                continue; // Move to next row (data rows)
            } else if (!treatFirstRowAsHeaderInOutput && rowIndex == 0 && header != null && originalRows.size() > 1) {
                // If the first row was a header for detection but should NOT be in the output data lines, skip it.
                // This logic becomes tricky. For now, let's simplify: if treatFirstRowAsHeaderInOutput is false,
                // it means the file has no header line to be outputted.
                // This needs careful consideration based on UI choice.
                // Current simple approach: if it's a header used for detection, and we are NOT outputting headers, then skip row 0
                if (header != null && !treatFirstRowAsHeaderInOutput && parsedFile.isFirstRowHeaderForDetection()) {
                    // This implies the UI determined the first row IS a header and should NOT be part of data
                    if (rowIndex == 0) continue;
                }
            }


            List<String> keptColumns = new ArrayList<>();
            for (int i = 0; i < originalRow.length; i++) {
                if (!ignoreIndices.contains(i)) {
                    keptColumns.add(originalRow[i]);
                }
            }
            // Only add line if it's not empty after filtering
            if (!keptColumns.isEmpty() || originalRow.length == 0) { // Add empty lines if they were originally empty
                processedLines.add(String.join(",", keptColumns.toArray(new String[0])));
            }
        }
        return processedLines;
    }

    // Helper DTO for parseFileIntoRowsAndHeader
    @lombok.Data // from project Lombok
    @lombok.AllArgsConstructor
    public static class ParsedFileResult {
        private List<String[]> rows;
        private String[] header;
        private boolean firstRowHeaderForDetection = true; // Default true if header is found

        public ParsedFileResult(List<String[]> rows, String[] header) {
            this.rows = rows;
            this.header = header;
            this.firstRowHeaderForDetection = (header != null);
        }
    }

    // Existing parse methods modified to be private helpers returning List<String> for simple cases
    private List<String> parseTextToList(InputStream inputStream) throws IOException { /* ... as before, returns List<String> ... */
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
    private List<String> parseJsonToList(InputStream inputStream, Path originalFilePath) throws IOException { /* ... as before, returns List<String> ... */
        try {
            JsonNode rootNode = objectMapper.readTree(inputStream);
            String prettyJson = objectMapper.writeValueAsString(rootNode);
            return Arrays.asList(prettyJson.split("\\R"));
        } catch (JsonProcessingException e) {
            logger.warn("Malformed JSON [{}], attempting to parse as text. Error: {}", originalFilePath.getFileName(), e.getMessage());
            try (InputStream textStream = Files.newInputStream(originalFilePath)) {
                return parseTextToList(textStream);
            }
        } catch (IOException e) {
            throw e;
        }
    }
}