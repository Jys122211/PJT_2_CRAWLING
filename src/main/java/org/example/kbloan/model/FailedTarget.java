package org.example.kbloan.model;

/**
 * 목록에는 있었지만 상세 수집에 실패한 상품.
 *
 * 이걸 따로 들고 다니지 않으면 "잠깐 페이지가 안 열린 상품"과
 * "KB에서 내려간 상품"을 구분할 수 없어, 멀쩡한 상품을 논리삭제하게 된다.
 */
public record FailedTarget(
        ProductSummary product,
        String reason
) {
}
