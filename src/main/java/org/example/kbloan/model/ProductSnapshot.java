package org.example.kbloan.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 한 상품의 "이번 수집 시점 모습".
 * credit_loan_products_history 한 줄 + 그에 매달릴 금리 줄들에 대응한다.
 * 이력 저장과 라이브 저장이 모두 이 객체 하나만 보고 동작한다.
 */
public record ProductSnapshot(
        /** 라이브 상품 ID. 신규라 아직 없으면 null */
        Long loanProductId,
        String productName,
        String productNameKey,
        String detailUrl,
        /** 한도 파싱 실패 시 null */
        Long loanLimit,
        String loanLimitRaw,
        BigDecimal maxDiscountRate,
        /** PRESENT | REMOVED */
        String sourceState,
        /** OK | FAILED */
        String collectStatus,
        String failureReason,
        List<ResolvedRate> rates,
        /** 상세 페이지에서 읽어낸 우대조건 */
        List<CrawledCondition> preferentialConditions,
        /** 상세 페이지에서 읽어낸 자격조건 */
        List<CrawledCondition> qualificationConditions
) {
    public static final String STATE_PRESENT = "PRESENT";
    public static final String STATE_REMOVED = "REMOVED";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";

    public boolean isStorable() {
        return STATUS_OK.equals(collectStatus) && !rates.isEmpty();
    }
}
