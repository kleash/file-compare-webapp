package com.github.kleash.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@Data
public class ComparisonInput {
    private List<MultipartFile> source1Files;
    private List<MultipartFile> source2Files;
    private boolean sortFiles; // Sort by filename before pairing
}