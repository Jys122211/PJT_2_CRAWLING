package org.example.kbloan.model;

import java.math.BigDecimal;

/**
 * 저장 직전 단계까지 계산이 끝난 금리 한 줄.
 * 이력 테이블과 라이브 테이블이 같은 값을 쓰도록 여기서 한 번만 계산한다.
 */
public record ResolvedRate(
        int periodMonths,
        int creditGrade,
        BigDecimal baseRate,
        BigDecimal spreadRate,
        /** CRAWLED = 사이트 원본(3등급) | DERIVED = finlife 비율로 환산 */
        String origin,
        /** 수집 당시 금리구분 원문. 예: "CD 91일물" */
        String rateTypeRaw
) {
    public static final String ORIGIN_CRAWLED = "CRAWLED";
    public static final String ORIGIN_DERIVED = "DERIVED";
}
