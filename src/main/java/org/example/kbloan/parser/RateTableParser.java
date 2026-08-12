package org.example.kbloan.parser;

import org.example.kbloan.model.LoanRateRow;
import org.example.kbloan.util.TextUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.math.BigDecimal;
import java.util.*;

public final class RateTableParser {

    public List<LoanRateRow> parse(Document document) {
        List<LoanRateRow> result = new ArrayList<>();

        for (Element table : document.select("table")) {
            String tableText = TextUtils.oneLine(table.text());

            if (!looksLikeRateTable(tableText)) {
                continue;
            }

            List<String> headers = readHeaders(table);
            if (headers.isEmpty()) {
                continue;
            }

            for (Element row : table.select("tbody tr, tr")) {
                Elements cells = row.select("> th, > td");

                if (cells.isEmpty()
                        || row.select("td").isEmpty()) {
                    continue;
                }

                Map<String, String> columns =
                        mapColumns(headers, cells);

                LoanRateRow parsed = toRateRow(columns);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
        }

        return deduplicate(result);
    }

    private boolean looksLikeRateTable(String text) {
        return text.contains("기준금리")
                && text.contains("가산금리")
                && (text.contains("최저금리")
                    || text.contains("최저"))
                && (text.contains("최고금리")
                    || text.contains("최고"));
    }

    private List<String> readHeaders(Element table) {
        Element headerRow = table.selectFirst("thead tr");

        if (headerRow == null) {
            for (Element row : table.select("tr")) {
                if (!row.select("th").isEmpty()) {
                    headerRow = row;
                    break;
                }
            }
        }

        if (headerRow == null) {
            return List.of();
        }

        List<String> headers = new ArrayList<>();

        for (Element cell : headerRow.select("> th, > td")) {
            String header = TextUtils.oneLine(cell.text());
            int colspan = parseColspan(cell);

            if (colspan == 2
                    && TextUtils.normalizedHeader(header)
                    .contains(TextUtils.normalizedHeader("기준금리"))) {
                headers.add("금리유형");
                headers.add("기준금리");
                continue;
            }

            for (int index = 0; index < colspan; index++) {
                headers.add(header);
            }
        }

        return headers;
    }

    private int parseColspan(Element cell) {
        try {
            return Math.max(1, Integer.parseInt(cell.attr("colspan")));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private Map<String, String> mapColumns(
            List<String> headers,
            Elements cells
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        int size = Math.min(headers.size(), cells.size());

        for (int index = 0; index < size; index++) {
            values.put(
                    headers.get(index),
                    TextUtils.oneLine(cells.get(index).text())
            );
        }

        return values;
    }

    private LoanRateRow toRateRow(
            Map<String, String> columns
    ) {
        String category = valueByAlias(
                columns,
                "금리구분",
                "구분",
                "상품구분",
                "대출금리구분"
        );

        String rateType = valueByAlias(
                columns,
                "기준금리",
                "금리유형",
                "금리종류"
        );

        BigDecimal baseRate = decimalByAlias(
                columns,
                "기준금리"
        );

        BigDecimal spreadRate = decimalByAlias(
                columns,
                "가산금리"
        );

        BigDecimal preferentialRate = decimalByAlias(
                columns,
                "우대금리"
        );

        BigDecimal minimumRate = decimalByAlias(
                columns,
                "최저금리",
                "최저"
        );

        BigDecimal maximumRate = decimalByAlias(
                columns,
                "최고금리",
                "최고"
        );

        if (baseRate == null
                && spreadRate == null
                && minimumRate == null
                && maximumRate == null) {
            return null;
        }

        return new LoanRateRow(
                category,
                rateType,
                baseRate,
                spreadRate,
                preferentialRate,
                minimumRate,
                maximumRate,
                columns
        );
    }

    private String valueByAlias(
            Map<String, String> columns,
            String... aliases
    ) {
        for (Map.Entry<String, String> entry
                : columns.entrySet()) {

            String normalized =
                    TextUtils.normalizedHeader(entry.getKey());

            for (String alias : aliases) {
                if (normalized.contains(
                        TextUtils.normalizedHeader(alias)
                )) {
                    return entry.getValue();
                }
            }
        }

        return "";
    }

    private BigDecimal decimalByAlias(
            Map<String, String> columns,
            String... aliases
    ) {
        return TextUtils.firstDecimal(
                valueByAlias(columns, aliases)
        );
    }

    private List<LoanRateRow> deduplicate(
            List<LoanRateRow> input
    ) {
        Map<String, LoanRateRow> unique =
                new LinkedHashMap<>();

        for (LoanRateRow row : input) {
            String key = row.rawColumns().toString();
            unique.putIfAbsent(key, row);
        }

        return new ArrayList<>(unique.values());
    }
}
