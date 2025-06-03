package com.github.kleash.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManualPair {
    private String source1FileName;
    private String source2FileName;
}