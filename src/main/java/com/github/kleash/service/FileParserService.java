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

@Service
public class FileParserService {
    private static final Logger logger = LoggerFactory.getLogger(FileParserService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public List<String> parseFile(Path filePath) throws IOException, CsvException { // Changed signature
        if (filePath == null || !Files.exists(filePath) || Files.isDirectory(filePath)) {
            logger.warn("Attempted to parse null, non-existent, or directory path: {}", filePath);
            return new ArrayList<>();
        }
        FileTypeUtil.FileType type = FileTypeUtil.getFileType(filePath.getFileName().toString());
        try (InputStream inputStream = Files.newInputStream(filePath)) { // Use Files.newInputStream
            switch (type) {
                case CSV:
                    return parseCsv(inputStream);
                case EXCEL_XLS:
                case EXCEL_XLSX:
                    return parseExcel(inputStream, type);
                case JSON:
                    // Pass filePath for potential re-read in fallback
                    return parseJson(inputStream, filePath);
                case TEXT:
                    return parseText(inputStream);
                default:
                    throw new IllegalArgumentException("Unsupported file type: " + filePath.getFileName().toString());
            }
        }
    }

    private List<String> parseText(InputStream inputStream) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private List<String> parseJson(InputStream inputStream, Path originalFilePath) throws IOException {
        try {
            JsonNode rootNode = objectMapper.readTree(inputStream);
            String prettyJson = objectMapper.writeValueAsString(rootNode);
            return Arrays.asList(prettyJson.split("\\R"));
        } catch (JsonProcessingException e) {
            logger.warn("Malformed JSON [{}], attempting to parse as text. Error: {}", originalFilePath.getFileName(), e.getMessage());
            // Fallback to text parsing using the original file path to re-open the stream
            try (InputStream textStream = Files.newInputStream(originalFilePath)) {
                return parseText(textStream);
            }
        } catch (IOException e) { // Catch other IOExceptions from objectMapper.readTree or initial stream
            throw e;
        }
    }

    private List<String> parseCsv(InputStream inputStream) throws IOException, CsvException {
        List<String> lines = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            List<String[]> allRows = reader.readAll();
            for (String[] row : allRows) {
                lines.add(String.join(",", row));
            }
        }
        return lines;
    }

    private List<String> parseExcel(InputStream inputStream, FileTypeUtil.FileType type) throws IOException {
        List<String> lines = new ArrayList<>();
        Workbook workbook;
        if (type == FileTypeUtil.FileType.EXCEL_XLSX) {
            workbook = new XSSFWorkbook(inputStream);
        } else if (type == FileTypeUtil.FileType.EXCEL_XLS) {
            workbook = new HSSFWorkbook(inputStream);
        } else {
            throw new IllegalArgumentException("Not an Excel file type for parseExcel method");
        }

        DataFormatter dataFormatter = new DataFormatter();
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                List<String> cellValues = new ArrayList<>();
                for (Cell cell : row) {
                    cellValues.add(dataFormatter.formatCellValue(cell));
                }
                lines.add(String.join(",", cellValues));
            }
        }
        workbook.close();
        return lines;
    }
}