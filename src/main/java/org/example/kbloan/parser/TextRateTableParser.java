package org.example.kbloan.parser;

import org.example.kbloan.model.LoanRateRow;
import org.example.kbloan.util.TextUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextRateTableParser {

    private static final Pattern RATE_ROW = Pattern.compile(
            "^(.+?)\\s+"
                    + "(-?\\d+(?:\\.\\d+)?)\\s+"
                    + "(-?\\d+(?:\\.\\d+)?)\\s+"
                    + "(-?\\d+(?:\\.\\d+)?)\\s+"
                    + "(-?\\d+(?:\\.\\d+)?)\\s+"
                    + "(-?\\d+(?:\\.\\d+)?)$"
    );

    public List<LoanRateRow> parse(String visibleText) {
        if (visibleText == null || visibleText.isBlank()) {
            return List.of();
        }

        String currentGroup = "";
        String previousLine = "";
        boolean insideRateTable = false;

        List<LoanRateRow> result = new ArrayList<>();

        String currentText = new TextSectionExtractor()
                .removeHistorySection(visibleText);

        for (String rawLine : currentText.split("\\R")) {
            String line = TextUtils.oneLine(rawLine);

            if (line.isBlank()) {
                continue;
            }

            if (line.contains("대출금리표")
                    && line.contains("기준금리")
                    && line.contains("가산금리")
                    && line.contains("우대금리")
                    && line.contains("최저금리")
                    && line.contains("최고금리")) {
                insideRateTable = true;
                currentGroup = previousLine;
                previousLine = line;
                continue;
            }

            if (insideRateTable) {
                Matcher matcher = RATE_ROW.matcher(line);

                if (matcher.matches()) {
                    Map<String, String> rawColumns =
                            new LinkedHashMap<>();

                    rawColumns.put("금리유형", matcher.group(1));
                    rawColumns.put("기준금리", matcher.group(2));
                    rawColumns.put("가산금리", matcher.group(3));
                    rawColumns.put("우대금리", matcher.group(4));
                    rawColumns.put("최저금리", matcher.group(5));
                    rawColumns.put("최고금리", matcher.group(6));

                    result.add(new LoanRateRow(
                            currentGroup,
                            matcher.group(1),
                            decimal(matcher.group(2)),
                            decimal(matcher.group(3)),
                            decimal(matcher.group(4)),
                            decimal(matcher.group(5)),
                            decimal(matcher.group(6)),
                            rawColumns
                    ));

                    previousLine = line;
                    continue;
                }

                if (line.contains("대출금리표")) {
                    currentGroup = previousLine;
                    previousLine = line;
                    continue;
                }

                if (line.startsWith("(1)")
                        || line.startsWith("1)")
                        || line.contains("기준금리 :")
                        || line.contains("중도상환수수료")) {
                    insideRateTable = false;
                }
            }

            previousLine = line;
        }

        return deduplicate(result);
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private List<LoanRateRow> deduplicate(
            List<LoanRateRow> input
    ) {
        Map<String, LoanRateRow> unique =
                new LinkedHashMap<>();

        for (LoanRateRow row : input) {
            String key = row.rateCategory()
                    + "|"
                    + row.rateType()
                    + "|"
                    + row.rawColumns();

            unique.putIfAbsent(key, row);
        }

        return new ArrayList<>(unique.values());
    }
}
