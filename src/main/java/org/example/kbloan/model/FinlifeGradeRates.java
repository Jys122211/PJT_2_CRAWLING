package org.example.kbloan.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finlife의 등급별 기준금리(B)와 가산금리(C).
 * 내부 신용등급 1~4는 각각 crdt_grad_1, crdt_grad_4,
 * crdt_grad_5, crdt_grad_6에 대응한다.
 */
public record FinlifeGradeRates(
        String disclosureMonth,
        Map<Integer, BigDecimal> baseRates,
        Map<Integer, BigDecimal> spreadRates
) {
    private static final int BASELINE_GRADE = 3;

    public FinlifeGradeRates {
        baseRates = validatedCopy(baseRates, "기준금리");
        spreadRates = validatedCopy(spreadRates, "가산금리");
    }

    public static FinlifeGradeRates fixedJune2026() {
        return new FinlifeGradeRates(
                "202606",
                Map.of(
                        1, new BigDecimal("2.87"),
                        2, new BigDecimal("2.88"),
                        3, new BigDecimal("2.89"),
                        4, new BigDecimal("2.94")
                ),
                Map.of(
                        1, new BigDecimal("2.22"),
                        2, new BigDecimal("2.65"),
                        3, new BigDecimal("3.16"),
                        4, new BigDecimal("2.49")
                )
        );
    }

    public BigDecimal baseRate(int creditGrade) {
        return baseRates.get(creditGrade);
    }

    public BigDecimal spreadRate(int creditGrade) {
        return spreadRates.get(creditGrade);
    }

    public BigDecimal baselineBaseRate() {
        return baseRate(BASELINE_GRADE);
    }

    public BigDecimal baselineSpreadRate() {
        return spreadRate(BASELINE_GRADE);
    }

    private static Map<Integer, BigDecimal> validatedCopy(
            Map<Integer, BigDecimal> rates,
            String label
    ) {
        Map<Integer, BigDecimal> copy = new LinkedHashMap<>();

        for (int grade = 1; grade <= 4; grade++) {
            BigDecimal rate = rates == null ? null : rates.get(grade);
            if (rate == null) {
                throw new IllegalArgumentException(
                        label + "의 " + grade + "등급 값이 없습니다."
                );
            }
            copy.put(grade, rate);
        }

        if (copy.get(BASELINE_GRADE).compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException(
                    label + "의 3등급 값은 0일 수 없습니다."
            );
        }

        return Map.copyOf(copy);
    }
}
