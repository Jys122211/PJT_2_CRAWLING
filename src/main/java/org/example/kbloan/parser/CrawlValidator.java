package org.example.kbloan.parser;

import org.example.kbloan.model.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class CrawlValidator {

    private static final BigDecimal RATE_TOLERANCE =
            new BigDecimal("0.03");

    public ValidationResult validate(
            ProductSummary product,
            String eligibility,
            List<LoanRateRow> rates,
            List<PreferentialItem> preferences
    ) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (product.productName() == null
                || product.productName().isBlank()) {
            errors.add("상품명이 없습니다.");
        }

        if (product.productCode() == null
                || product.productCode().isBlank()) {
            warnings.add(
                    "상품코드를 목록 페이지에서 찾지 못했습니다."
            );
        }

        if (eligibility == null
                || eligibility.isBlank()) {
            warnings.add(
                    "대출신청자격 섹션을 찾지 못했습니다."
            );
        }

        if (rates.isEmpty()) {
            warnings.add("금리표를 찾지 못했습니다.");
        }

        if (preferences.isEmpty()) {
            warnings.add(
                    "구조화된 우대금리 항목을 찾지 못했습니다."
            );
        }

        for (LoanRateRow rate : rates) {
            if (rate.minimumRate() != null
                    && rate.maximumRate() != null
                    && rate.minimumRate().compareTo(
                            rate.maximumRate()
                    ) > 0) {
                errors.add(
                        "최저금리가 최고금리보다 큽니다: "
                                + rate.rawColumns()
                );
            }

            validateRateFormula(rate, warnings);
        }

        String status = errors.isEmpty()
                ? warnings.isEmpty()
                    ? "VALID"
                    : "WARNING"
                : "INVALID";

        return new ValidationResult(
                status,
                List.copyOf(errors),
                List.copyOf(warnings)
        );
    }

    private void validateRateFormula(
            LoanRateRow rate,
            List<String> warnings
    ) {
        if (rate.baseRate() == null
                || rate.spreadRate() == null
                || rate.preferentialRate() == null
                || rate.minimumRate() == null) {
            return;
        }

        BigDecimal calculated = rate.baseRate()
                .add(rate.spreadRate())
                .subtract(rate.preferentialRate());

        BigDecimal difference = calculated
                .subtract(rate.minimumRate())
                .abs();

        if (difference.compareTo(RATE_TOLERANCE) > 0) {
            warnings.add(
                    "기준금리+가산금리-우대금리와 "
                            + "최저금리가 일치하지 않습니다: "
                            + rate.rawColumns()
            );
        }
    }
}
