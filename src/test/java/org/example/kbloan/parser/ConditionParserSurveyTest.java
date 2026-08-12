package org.example.kbloan.parser;

import org.example.kbloan.model.CrawledCondition;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 저장된 상세 페이지들에서 조건이 어떻게 읽히는지 눈으로 확인한다. */
class ConditionParserSurveyTest {

    private final ConditionParser parser = new ConditionParser();

    @Test
    void 저장된_모든_상품의_조건을_출력한다() throws Exception {
        for (String code : List.of("LN20001347", "LN20001391", "LN20001397",
                "LN20001400", "LN20001401")) {
            String html = Files.readString(Path.of("output/raw/" + code + ".html"));

            System.out.println("========== " + code);
            List<CrawledCondition> pref = parser.parsePreferential(html);
            System.out.println("우대조건 " + pref.size() + "개");
            pref.forEach(c -> System.out.println(
                    "   " + c.discountRate() + "%p | " + c.name()));

            List<CrawledCondition> qual = parser.parseQualification(html);
            System.out.println("자격조건 " + qual.size() + "개");
            qual.forEach(c -> System.out.println("   " + c.name()));
        }
    }
}
