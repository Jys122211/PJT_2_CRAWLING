package org.example.kbloan.model;

import java.util.List;

public record ValidationResult(
        String status,
        List<String> errors,
        List<String> warnings
) {
}
