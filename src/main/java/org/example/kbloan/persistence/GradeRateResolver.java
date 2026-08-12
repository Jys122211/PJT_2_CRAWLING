package org.example.kbloan.persistence;

import org.example.kbloan.model.FinlifeGradeRates;
import org.example.kbloan.model.LoanProductDetail;
import org.example.kbloan.model.LoanRateRow;
import org.example.kbloan.model.ResolvedRate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 크롤링한 금리행을 (금리주기 × 신용등급) 조합으로 펼친다.
 *
 * KB가 알려주는 값은 3등급 한 세트뿐이다. 1·2·4등급은 finlife 공시 비율로
 * 환산한 추정치이므로, 어느 쪽인지 origin 에 남긴다.
 */
public final class GradeRateResolver {

    /** 사이트에서 실제로 긁어오는 등급. 나머지는 이 값에서 환산한다. */
    private static final int CRAWLED_GRADE = 3;

    private final GradeRateCalculator calculator = new GradeRateCalculator();

    public List<ResolvedRate> resolve(
            LoanProductDetail product,
            Optional<FinlifeGradeRates> finlifeRates
    ) {
        List<ResolvedRate> resolved = new ArrayList<>();

        for (Map.Entry<Integer, LoanRateRow> entry
                : baselineRatesByPeriod(product).entrySet()) {

            int periodMonths = entry.getKey();
            LoanRateRow baseline = entry.getValue();
            String rateTypeRaw = baseline.rateType();

            if (finlifeRates.isEmpty()) {
                resolved.add(new ResolvedRate(
                        periodMonths,
                        CRAWLED_GRADE,
                        baseline.baseRate(),
                        baseline.spreadRate(),
                        ResolvedRate.ORIGIN_CRAWLED,
                        rateTypeRaw
                ));
                continue;
            }

            for (int grade = 1; grade <= 4; grade++) {
                if (grade == CRAWLED_GRADE) {
                    // finlife 기준등급도 3등급이라 환산해도 같은 값이 나온다.
                    // 굳이 계산을 태우지 않고 원본을 그대로 쓴다.
                    resolved.add(new ResolvedRate(
                            periodMonths,
                            grade,
                            baseline.baseRate(),
                            baseline.spreadRate(),
                            ResolvedRate.ORIGIN_CRAWLED,
                            rateTypeRaw
                    ));
                    continue;
                }

                GradeRateCalculator.CalculatedRate calculated =
                        calculator.calculate(
                                baseline.baseRate(),
                                baseline.spreadRate(),
                                grade,
                                finlifeRates.get()
                        );

                resolved.add(new ResolvedRate(
                        periodMonths,
                        grade,
                        calculated.baseRate(),
                        calculated.spreadRate(),
                        ResolvedRate.ORIGIN_DERIVED,
                        rateTypeRaw
                ));
            }
        }

        return List.copyOf(resolved);
    }

    private Map<Integer, LoanRateRow> baselineRatesByPeriod(
            LoanProductDetail product
    ) {
        Map<Integer, LoanRateRow> result = new LinkedHashMap<>();

        for (LoanRateRow row : product.rateRows()) {
            Integer period = resolvePeriod(row);
            if (period == null) {
                System.out.println(
                        "금리주기 해석 실패: "
                                + product.product().productName()
                                + " / " + row.rateType()
                );
                continue;
            }

            LoanRateRow previous = result.putIfAbsent(period, row);
            if (previous != null) {
                throw new IllegalStateException(
                        product.product().productName()
                                + "의 " + period
                                + "개월 금리행이 둘 이상입니다. "
                                + "DB 스키마에 금리구분 컬럼을 추가하거나 "
                                + "저장할 행의 선택 기준을 정해야 합니다."
                );
            }
        }

        return result;
    }

    private Integer resolvePeriod(LoanRateRow row) {
        String text = String.join(
                " ",
                Objects.toString(row.rateCategory(), ""),
                Objects.toString(row.rateType(), ""),
                Objects.toString(row.rawColumns(), "")
        ).replaceAll("\\s+", "");

        if (text.contains("91일") || text.contains("3개월")) {
            return 3;
        }
        if (text.contains("6개월")) {
            return 6;
        }
        if (text.contains("12개월") || text.contains("1년")) {
            return 12;
        }
        return null;
    }
}
