package org.example.kbloan.model;

import java.math.BigDecimal;
import java.util.Map;

public record LoanRateRow(
        String rateCategory,
        String rateType,
        BigDecimal baseRate,
        BigDecimal spreadRate,
        BigDecimal preferentialRate,
        BigDecimal minimumRate,
        BigDecimal maximumRate,
        Map<String, String> rawColumns
) {
}
