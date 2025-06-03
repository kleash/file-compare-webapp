package com.github.kleash.util;

import org.apache.commons.io.FilenameUtils;

public class FileTypeUtil {
    public enum FileType { CSV, EXCEL_XLS, EXCEL_XLSX, JSON, TEXT, UNSUPPORTED }

    public static FileType getFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return FileType.UNSUPPORTED;
        }
        String extension = FilenameUtils.getExtension(fileName.toLowerCase());
        switch (extension) {
            case "csv":
                return FileType.CSV;
            case "xls":
                return FileType.EXCEL_XLS;
            case "xlsx":
                return FileType.EXCEL_XLSX;
            case "json":
                return FileType.JSON;
            case "txt":
            case "log": // Add other text-based extensions
                return FileType.TEXT;
            default:
                return FileType.UNSUPPORTED;
        }
    }
}