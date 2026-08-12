package org.example.kbloan.parser;

import org.example.kbloan.model.*;
import org.example.kbloan.util.TextUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.time.Instant;
import java.util.*;

public final class LoanDetailParser {

    private final SectionExtractor domSectionExtractor =
            new SectionExtractor();

    private final TextSectionExtractor textSectionExtractor =
            new TextSectionExtractor();

    private final RateTableParser htmlRateTableParser =
            new RateTableParser();

    private final TextRateTableParser textRateTableParser =
            new TextRateTableParser();

    private final PreferentialParser preferentialParser =
            new PreferentialParser();

    public LoanProductDetail parse(
            ProductSummary product,
            String html
    ) {
        Document document = Jsoup.parse(
                html,
                product.detailUrl()
        );

        return parse(
                product,
                html,
                document.wholeText()
        );
    }

    public LoanProductDetail parse(
            ProductSummary product,
            String html,
            String visibleText
    ) {
        Document document = Jsoup.parse(
                html,
                product.detailUrl()
        );

        String eligibility =
                textSectionExtractor.extract(
                        visibleText,
                        List.of(
                                "대출신청자격",
                                "대출대상"
                        ),
                        List.of(
                                "대출금액",
                                "대출한도",
                                "대출기간",
                                "대출기간 및 상환 방법",
                                "대출대상주택"
                        )
                );

        if (eligibility.isBlank()) {
            eligibility = domSectionExtractor.extract(
                    document,
                    List.of(
                            "대출신청자격",
                            "대출대상"
                    ),
                    List.of(
                            "대출금액",
                            "대출한도",
                            "대출기간",
                            "대상주택"
                    )
            );
        }

        String preferential =
                textSectionExtractor.extract(
                        visibleText,
                        List.of(
                                "(3)우대금리",
                                "(3) 우대금리"
                        ),
                        List.of(
                                "(4)최종금리",
                                "(4) 최종금리",
                                "최종금리",
                                "중도상환수수료"
                        )
                );

        if (preferential.isBlank()) {
            preferential =
                    extractPreferentialFallback(
                            visibleText
                    );
        }

        List<LoanRateRow> rateRows =
                mergeRates(
                        htmlRateTableParser.parse(document),
                        textRateTableParser.parse(visibleText)
                );

        List<PreferentialItem> preferentialItems =
                preferentialParser.parse(preferential);

        ValidationResult validation = validate(
                product,
                eligibility,
                rateRows,
                preferentialItems
        );

        return new LoanProductDetail(
                product,
                Instant.now().toString(),
                TextUtils.normalize(eligibility),
                TextUtils.normalize(preferential),
                rateRows,
                preferentialItems,
                validation,
                List.of(),
                List.of()
        );
    }

    public boolean looksLikeDetailPage(
            String html,
            String visibleText
    ) {
        String text = TextUtils.oneLine(
                visibleText == null || visibleText.isBlank()
                        ? Jsoup.parse(html).text()
                        : visibleText
        );

        return text.contains("대출신청자격")
                || text.contains("대출금리")
                || text.contains("금리 및 이율")
                || text.contains("(3)우대금리");
    }

    private String extractPreferentialFallback(
            String visibleText
    ) {
        String currentText =
                textSectionExtractor.removeHistorySection(
                        visibleText
                );

        String[] lines = currentText.split("\\R");

        int start = -1;

        for (int index = 0;
             index < lines.length;
             index++) {

            String line = TextUtils.oneLine(lines[index]);

            boolean actualSection =
                    line.contains("우대금리")
                            && !line.contains("대출금리표")
                            && (
                            line.contains("최고 연")
                                    || line.startsWith("(3)")
                    );

            if (actualSection) {
                start = index;
                break;
            }
        }

        if (start < 0) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (int index = start + 1;
             index < lines.length;
             index++) {

            String line = TextUtils.oneLine(lines[index]);

            if (line.startsWith("(4)")
                    || line.startsWith("최종금리")
                    || line.contains("중도상환수수료")) {
                break;
            }

            if (!line.isBlank()) {
                result.append(line).append('\n');
            }
        }

        return TextUtils.normalize(result.toString());
    }

    private List<LoanRateRow> mergeRates(
            List<LoanRateRow> htmlRates,
            List<LoanRateRow> textRates
    ) {
        Map<String, LoanRateRow> unique =
                new LinkedHashMap<>();

        for (LoanRateRow row : htmlRates) {
            unique.put(rateKey(row), row);
        }

        for (LoanRateRow row : textRates) {
            unique.putIfAbsent(rateKey(row), row);
        }

        return new ArrayList<>(unique.values());
    }

    private String rateKey(LoanRateRow row) {
        return row.rateType()
                + "|"
                + row.baseRate()
                + "|"
                + row.spreadRate()
                + "|"
                + row.preferentialRate()
                + "|"
                + row.minimumRate()
                + "|"
                + row.maximumRate();
    }

    private ValidationResult validate(
            ProductSummary product,
            String eligibility,
            List<LoanRateRow> rates,
            List<PreferentialItem> preferences
    ) {
        return new CrawlValidator().validate(
                product,
                eligibility,
                rates,
                preferences
        );
    }
}
