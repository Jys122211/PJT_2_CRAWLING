package org.example.kbloan.model;

import java.util.List;

public record LoanProductDetail(
        ProductSummary product,
        String collectedAt,
        String eligibilityRawText,
        String preferentialRawText,
        List<LoanRateRow> rateRows,
        List<PreferentialItem> preferentialItems,
        ValidationResult validation,
        /** 상세 페이지에서 항목별로 읽어낸 우대조건 */
        List<CrawledCondition> preferentialConditions,
        /** 상세 페이지에서 항목별로 읽어낸 자격조건 */
        List<CrawledCondition> qualificationConditions
) {
    /** 조건 파싱 결과를 붙인 사본을 만든다. */
    public LoanProductDetail withConditions(
            List<CrawledCondition> preferentialConditions,
            List<CrawledCondition> qualificationConditions
    ) {
        return new LoanProductDetail(
                product,
                collectedAt,
                eligibilityRawText,
                preferentialRawText,
                rateRows,
                preferentialItems,
                validation,
                preferentialConditions,
                qualificationConditions
        );
    }
}
