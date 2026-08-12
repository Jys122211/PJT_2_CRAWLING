package org.example.kbloan.collector;

import org.example.kbloan.model.ProductSummary;

import java.util.List;

/**
 * 목록 파싱이 실패했을 때 쓰는 최후의 상품 목록.
 *
 * 여기 적힌 상품명·한도는 사이트에서 읽은 값이 아니라 사람이 손으로 적어둔 값이다.
 * 이 카탈로그가 쓰이면 실행 로그에 FALLBACK_CATALOG 로 찍히므로,
 * "이 값은 사이트에서 읽은 게 아니다"를 그때 알 수 있다.
 */
public final class KnownProductCatalog {

    private static final String CREDIT_LOAN_CODE = "LN20001397";

    private KnownProductCatalog() {
    }

    public static List<ProductSummary> creditLoanFallback(
            CrawlUrlSettings urlSettings
    ) {
        return List.of(
                new ProductSummary(
                        CREDIT_LOAN_CODE,
                        "KB스타 신용대출Ⅱ",
                        "증빙소득이 발생하는 직장인을 위한 비대면 신용대출",
                        "스타뱅킹",
                        "최대 3.5억원",
                        urlSettings.detailUrlFor(CREDIT_LOAN_CODE),
                        "FALLBACK_CATALOG"
                )
        );
    }
}
