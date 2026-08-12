package org.example.kbloan.model;

import java.util.List;

public record LoanCollectionResult(
        String sourceUrl,
        CollectMode collectMode,
        String collectedAt,
        int discoveredProductCount,
        int targetProductCount,
        List<String> collectionWarnings,
        List<LoanProductDetail> products,
        /** 목록에는 있었지만 상세 수집에 실패한 상품들 */
        List<FailedTarget> failedTargets
) {
}
