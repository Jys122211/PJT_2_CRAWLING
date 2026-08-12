package org.example.kbloan.persistence;

import org.example.kbloan.model.FinlifeGradeRates;
import org.example.kbloan.model.LoanProductDetail;
import org.example.kbloan.model.LoanRateRow;
import org.example.kbloan.model.ProductSnapshot;
import org.example.kbloan.model.ResolvedRate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 크롤링 결과(LoanProductDetail)를 저장 단위(ProductSnapshot)로 바꾼다.
 *
 * 이력과 라이브가 서로 다른 값을 쓰는 사고를 막기 위해, 한도 변환과 금리 환산은
 * 여기서 한 번만 한다.
 */
public final class ProductSnapshotFactory {

    private static final Pattern EOK_WON = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*억원"
    );
    private static final Pattern MAN_WON = Pattern.compile(
            "([0-9,]+)\\s*만원"
    );
    private static final Pattern WON = Pattern.compile(
            "([0-9,]+)\\s*원"
    );

    private final GradeRateResolver resolver = new GradeRateResolver();

    public ProductSnapshot create(
            LoanProductDetail product,
            Optional<FinlifeGradeRates> finlifeRates
    ) {
        String productName = product.product().productName();
        String limitText = product.product().limitText();

        List<ResolvedRate> rates;
        String collectStatus = ProductSnapshot.STATUS_OK;
        String failureReason = null;

        try {
            rates = resolver.resolve(product, finlifeRates);
            if (rates.isEmpty()) {
                collectStatus = ProductSnapshot.STATUS_FAILED;
                failureReason = "해석 가능한 금리행이 없습니다.";
            }
        } catch (RuntimeException exception) {
            // 금리주기가 겹치는 등 해석에 실패해도 "실패했다는 사실"은 남긴다.
            rates = List.of();
            collectStatus = ProductSnapshot.STATUS_FAILED;
            failureReason = truncate(exception.getMessage(), 500);
        }

        return new ProductSnapshot(
                null,
                productName,
                CreditLoanRateSynchronizer.canonicalProductName(productName),
                product.product().detailUrl(),
                parseLoanLimit(limitText),
                limitText,
                maxDiscountRate(product),
                ProductSnapshot.STATE_PRESENT,
                collectStatus,
                failureReason,
                rates,
                product.preferentialConditions(),
                product.qualificationConditions()
        );
    }

    /**
     * 상세 수집 자체가 실패해 상품 정보밖에 없는 경우.
     * 금리는 없지만 "이날 이 상품을 보려다 실패했다"를 남긴다.
     */
    public ProductSnapshot createFailed(
            String productName,
            String detailUrl,
            String failureReason
    ) {
        return new ProductSnapshot(
                null,
                productName,
                CreditLoanRateSynchronizer.canonicalProductName(productName),
                detailUrl,
                null,
                null,
                null,
                ProductSnapshot.STATE_PRESENT,
                ProductSnapshot.STATUS_FAILED,
                truncate(failureReason, 500),
                List.of(),
                List.of(),
                List.of()
        );
    }

    public static Long parseLoanLimit(String limitText) {
        if (limitText == null || limitText.isBlank()) {
            return null;
        }

        Matcher eok = EOK_WON.matcher(limitText);
        if (eok.find()) {
            return new BigDecimal(eok.group(1))
                    .multiply(new BigDecimal("100000000"))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        }

        Matcher man = MAN_WON.matcher(limitText);
        if (man.find()) {
            return Long.parseLong(man.group(1).replace(",", "")) * 10_000L;
        }

        Matcher won = WON.matcher(limitText);
        if (won.find()) {
            return Long.parseLong(won.group(1).replace(",", ""));
        }
        return null;
    }

    private BigDecimal maxDiscountRate(LoanProductDetail product) {
        return product.rateRows().stream()
                .map(LoanRateRow::preferentialRate)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
}
