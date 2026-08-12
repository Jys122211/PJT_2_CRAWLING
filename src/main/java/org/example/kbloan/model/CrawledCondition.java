package org.example.kbloan.model;

import java.math.BigDecimal;

/**
 * 상세 페이지에서 읽어낸 조건 한 건.
 *
 * 우대조건이면 discountRate 가 있고, 자격조건이면 null 이다.
 *
 * sourceKey 는 DB 의 기존 질문과 대조할 때 쓰는 열쇠다.
 * 화면에 보여줄 문구는 개발자가 따로 다듬으므로, 크롤링 원문을 그대로 비교하면
 * 사람이 문구를 손보는 순간 매칭이 깨진다. 그래서 표시 문구가 아니라
 * 이 열쇠를 기준으로 "같은 조건인가"를 판단한다.
 */
public record CrawledCondition(
        /** 조건 이름. 예: "KB신용카드 이용실적 우대" */
        String name,
        /** 상세 설명 원문 */
        String detail,
        /** 우대율(%). 자격조건은 null */
        BigDecimal discountRate,
        /** 대조용 열쇠. 이름(공백제거) + 우대율 */
        String sourceKey
) {
    public static CrawledCondition preferential(
            String name,
            String detail,
            BigDecimal discountRate
    ) {
        return new CrawledCondition(
                name, detail, discountRate,
                key(name, discountRate)
        );
    }

    public static CrawledCondition qualification(String text) {
        return new CrawledCondition(
                text, null, null, key(text, null)
        );
    }

    /**
     * 공백을 지우고 소문자로 맞춘다.
     * KB 표기가 "KB 스타뱅킹" 과 "KB스타뱅킹" 사이를 오가도 같은 조건으로 본다.
     *
     * DB 쪽 기존 질문도 같은 방식으로 열쇠를 만들어 대조한다.
     * 그래서 이 계산은 크롤링 값과 DB 값 양쪽에서 똑같이 쓰인다.
     */
    public static String keyOf(String name, BigDecimal rate) {
        return key(name, rate);
    }

    private static String key(String name, BigDecimal rate) {
        String normalized = name == null
                ? ""
                : name.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);

        return rate == null
                ? normalized
                : normalized + "|" + rate.stripTrailingZeros().toPlainString();
    }
}
