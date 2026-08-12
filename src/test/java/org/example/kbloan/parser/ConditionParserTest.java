package org.example.kbloan.parser;

import org.example.kbloan.model.CrawledCondition;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 저장해 둔 실제 KB 상세 페이지 HTML 로 검증한다.
 */
class ConditionParserTest {

    private final ConditionParser parser = new ConditionParser();

    private String html(String code) throws Exception {
        return Files.readString(Path.of("output/raw/" + code + ".html"));
    }

    @Test
    void 우대조건을_항목별로_읽는다() throws Exception {
        List<CrawledCondition> items =
                parser.parsePreferential(html("LN20001397"));

        items.forEach(item -> System.out.println(
                item.discountRate() + " | " + item.name() + " | " + item.detail()));

        assertFalse(items.isEmpty(), "우대조건을 하나도 못 읽었다");

        // 카드 실적은 금액 구간마다 우대율이 달라 3건으로 갈린다
        List<CrawledCondition> card = items.stream()
                .filter(i -> i.name().contains("KB신용카드"))
                .toList();
        assertEquals(3, card.size());
        assertEquals("0.1", card.get(0).discountRate().toPlainString());
        assertEquals("0.3", card.get(2).discountRate().toPlainString());

        assertTrue(items.stream().anyMatch(
                i -> i.name().contains("적립식예금")
                        && "0.1".equals(i.discountRate().toPlainString())));
        assertTrue(items.stream().anyMatch(
                i -> i.name().contains("자동이체")));
        assertTrue(items.stream().anyMatch(
                i -> i.name().contains("스타뱅킹")));
    }

    @Test
    void 공백이_달라도_같은_열쇠가_나온다() {
        assertEquals(
                CrawledCondition.preferential(
                        "KB 스타뱅킹 이용 우대", "", new java.math.BigDecimal("0.1")
                ).sourceKey(),
                CrawledCondition.preferential(
                        "KB스타뱅킹 이용 우대", "", new java.math.BigDecimal("0.10")
                ).sourceKey()
        );
    }

    @Test
    void 자격조건을_읽는다() throws Exception {
        List<CrawledCondition> items =
                parser.parseQualification(html("LN20001397"));

        items.forEach(item -> System.out.println("자격: " + item.name()));

        assertFalse(items.isEmpty(), "자격조건을 하나도 못 읽었다");
        assertTrue(items.stream().anyMatch(i -> i.name().contains("건강보험")));
    }
}
