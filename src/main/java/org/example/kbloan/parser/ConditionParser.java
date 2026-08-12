package org.example.kbloan.parser;

import org.example.kbloan.model.CrawledCondition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상세 페이지에서 우대조건·자격조건을 읽어낸다.
 *
 * 이 내용은 화면 텍스트(innerText)에는 안 나온다. 숨은 탭에 있어서 HTML 에만 있다.
 * 그래서 태그를 걷어낸 HTML 을 재료로 쓴다.
 *
 * KB 표기 형식:
 *   (3) 실적연동 우대금리 : 최고 연 0.9%p 우대
 *   ① KB신용카드 이용실적 우대 : 최고 연 0.3%p
 *   ☞ 결제계좌를 ... 30만원(연0.1%p)/60만원 (연0.2%p)/90만원(연 0.3%p)이상 ...
 *   ② 급여(연금)이체 관련 실적 우대 : 연 0.3%p
 *   ...
 */
public final class ConditionParser {

    private static final String PREFERENTIAL_ANCHOR = "실적연동 우대금리";
    private static final String QUALIFICATION_ANCHOR = "대출신청자격";

    /** 자격조건 구간의 끝. 이 문구가 나오면 멈춘다. */
    private static final String QUALIFICATION_END = "대출금액";

    private static final Pattern ITEM =
            Pattern.compile("[①②③④⑤⑥⑦⑧⑨⑩]\\s*([^:：]{2,60})[:：]\\s*([^①②③④⑤⑥⑦⑧⑨⑩※]*)");

    /** "연 0.3%p" / "0.3%p" / "연0.1%p" */
    private static final Pattern RATE =
            Pattern.compile("(?:연\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*%p");

    /** 카드 실적처럼 금액 구간마다 우대율이 다른 경우: "30만원(연0.1%p)" */
    private static final Pattern TIER =
            Pattern.compile("([0-9,]+\\s*만원)\\s*\\(\\s*연?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%p\\s*\\)");

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public List<CrawledCondition> parsePreferential(String html) {
        String text = plainText(html);

        int start = text.indexOf(PREFERENTIAL_ANCHOR);
        if (start < 0) {
            return List.of();
        }

        List<CrawledCondition> conditions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Matcher item = ITEM.matcher(text.substring(start));
        while (item.find()) {
            String name = item.group(1).trim();
            String body = item.group(2).trim();

            List<CrawledCondition> parsed = tiersOrSingle(name, body);
            for (CrawledCondition condition : parsed) {
                if (seen.add(condition.sourceKey())) {
                    conditions.add(condition);
                }
            }
        }

        return List.copyOf(conditions);
    }

    /**
     * 금액 구간이 여러 개면 구간마다 한 건씩 만든다.
     * "30만원(연0.1%p)/60만원(연0.2%p)/90만원(연0.3%p)" → 3건
     */
    private List<CrawledCondition> tiersOrSingle(String name, String body) {
        List<CrawledCondition> tiers = new ArrayList<>();

        Matcher tier = TIER.matcher(body);
        while (tier.find()) {
            tiers.add(CrawledCondition.preferential(
                    name,
                    SPACES.matcher(tier.group(1)).replaceAll("") + " 이상 이용",
                    new BigDecimal(tier.group(2))
            ));
        }

        if (!tiers.isEmpty()) {
            return tiers;
        }

        Matcher rate = RATE.matcher(body);
        if (!rate.find()) {
            return List.of();
        }

        // 우대율 뒤에 오는 "☞ ..." 설명만 남긴다.
        // 그냥 body 를 쓰면 "연 0.3%p ☞ 전월말기준..." 처럼 우대율이 중복된다.
        String detail = cleanDetail(
                body.substring(rate.end()).replaceFirst("^[\\s☞]+", "")
        );

        return List.of(CrawledCondition.preferential(
                name,
                detail,
                new BigDecimal(rate.group(1))
        ));
    }

    /**
     * 대출신청자격 구간을 항목으로 나눈다.
     *
     * 상품마다 표기가 다르다.
     *   LN20001397 : 표제 문장 + ☞ 세부 항목 2개
     *   LN20001401 : 표제 문장 하나뿐, ☞ 없음
     * 그래서 ☞ 로만 나누면 뒤쪽 상품은 조건을 하나도 못 읽는다.
     * 표제 문장도 어엿한 자격조건이므로 함께 담는다.
     */
    public List<CrawledCondition> parseQualification(String html) {
        String text = plainText(html);

        int start = text.indexOf(QUALIFICATION_ANCHOR);
        if (start < 0) {
            return List.of();
        }

        int end = text.indexOf(QUALIFICATION_END, start);
        String section = end > start
                ? text.substring(start, end)
                : text.substring(start);

        List<CrawledCondition> conditions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        String[] parts = section.split("☞");

        for (int i = 0; i < parts.length; i++) {
            String line = cleanDetail(parts[i]);

            if (i == 0) {
                // 첫 조각은 "대출신청자격 ..." 으로 시작한다. 표제어만 떼고 본문을 남긴다.
                line = cleanDetail(line.replaceFirst("^" + QUALIFICATION_ANCHOR, ""));
            }

            if (line.isBlank()) {
                continue;
            }

            CrawledCondition condition = CrawledCondition.qualification(line);
            if (seen.add(condition.sourceKey())) {
                conditions.add(condition);
            }
        }

        return List.copyOf(conditions);
    }

    private static String plainText(String html) {
        if (html == null) {
            return "";
        }
        String stripped = TAG.matcher(html).replaceAll(" ");
        return SPACES.matcher(stripped).replaceAll(" ");
    }

    private static String cleanDetail(String value) {
        return SPACES.matcher(value == null ? "" : value)
                .replaceAll(" ")
                .replaceFirst("^[\\s:：\\-·•*]+", "")
                .replaceFirst("[\\s:：\\-]+$", "")
                .trim();
    }
}
