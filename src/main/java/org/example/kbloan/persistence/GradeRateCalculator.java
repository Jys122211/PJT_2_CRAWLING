package org.example.kbloan.persistence;

import org.example.kbloan.model.FinlifeGradeRates;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class GradeRateCalculator {
    private static final int OUTPUT_SCALE = 2;

    public CalculatedRate calculate(
            BigDecimal crawledBaseRate,
            BigDecimal crawledSpreadRate,
            int creditGrade,
            FinlifeGradeRates finlifeRates
    ) {
        if (crawledBaseRate == null || crawledSpreadRate == null) {
            throw new IllegalArgumentException(
                    "크롤링 기준금리와 가산금리가 모두 필요합니다."
            );
        }

        BigDecimal baseRate = applyRatio(
                crawledBaseRate,
                finlifeRates.baseRate(creditGrade),
                finlifeRates.baselineBaseRate()
        );

        BigDecimal spreadRate = applyRatio(
                crawledSpreadRate,
                finlifeRates.spreadRate(creditGrade),
                finlifeRates.baselineSpreadRate()
        );

        return new CalculatedRate(baseRate, spreadRate);
    }

    private BigDecimal applyRatio(
            BigDecimal crawledGradeThreeRate,
            BigDecimal targetGradeApiRate,
            BigDecimal gradeThreeApiRate
    ) {
        return crawledGradeThreeRate
                .multiply(targetGradeApiRate)
                .divide(gradeThreeApiRate, 12, RoundingMode.HALF_UP)
                .setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    public record CalculatedRate(
            BigDecimal baseRate,
            BigDecimal spreadRate
    ) {
    }
}
