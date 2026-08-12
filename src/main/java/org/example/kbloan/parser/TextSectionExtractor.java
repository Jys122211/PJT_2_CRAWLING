package org.example.kbloan.parser;

import org.example.kbloan.util.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TextSectionExtractor {

    public String extract(
            String visibleText,
            List<String> startAliases,
            List<String> endAliases
    ) {
        List<String> lines = meaningfulLines(
                removeHistorySection(visibleText)
        );

        int startIndex = findStartIndex(lines, startAliases);
        if (startIndex < 0) {
            return "";
        }

        int endIndex = findEndIndex(
                lines,
                startIndex + 1,
                endAliases
        );

        StringBuilder result = new StringBuilder();

        for (int index = startIndex + 1;
             index < endIndex;
             index++) {
            result.append(lines.get(index)).append('\n');
        }

        return TextUtils.normalize(result.toString());
    }

    public String removeHistorySection(String value) {
        String normalized = normalizeLines(value);

        int historyIndex = indexOfAny(
                normalized,
                List.of(
                        "상품내용의 변경에 관한 사항",
                        "상품내용의 변경에 관한사항"
                )
        );

        if (historyIndex >= 0) {
            return normalized.substring(0, historyIndex);
        }

        return normalized;
    }

    private int findStartIndex(
            List<String> lines,
            List<String> aliases
    ) {
        for (int index = 0; index < lines.size(); index++) {
            String normalizedLine =
                    normalizeForComparison(lines.get(index));

            for (String alias : aliases) {
                String normalizedAlias =
                        normalizeForComparison(alias);

                if (normalizedLine.equals(normalizedAlias)
                        || normalizedLine.startsWith(
                                normalizedAlias + ":"
                        )
                        || normalizedLine.startsWith(
                                normalizedAlias + " "
                        )) {
                    return index;
                }
            }
        }

        return -1;
    }

    private int findEndIndex(
            List<String> lines,
            int fromIndex,
            List<String> aliases
    ) {
        for (int index = fromIndex;
             index < lines.size();
             index++) {

            String normalizedLine =
                    normalizeForComparison(lines.get(index));

            for (String alias : aliases) {
                String normalizedAlias =
                        normalizeForComparison(alias);

                if (normalizedLine.equals(normalizedAlias)
                        || normalizedLine.startsWith(
                                normalizedAlias + ":"
                        )
                        || normalizedLine.startsWith(
                                normalizedAlias + " "
                        )) {
                    return index;
                }
            }
        }

        return lines.size();
    }

    private List<String> meaningfulLines(String value) {
        String normalized = normalizeLines(value);
        List<String> lines = new ArrayList<>();

        for (String line : normalized.split("\\R")) {
            String cleaned = TextUtils.oneLine(line);

            if (!cleaned.isBlank()) {
                lines.add(cleaned);
            }
        }

        return lines;
    }

    private String normalizeLines(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    private String normalizeForComparison(String value) {
        return TextUtils.oneLine(value)
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }

    private int indexOfAny(
            String value,
            List<String> tokens
    ) {
        int result = -1;

        for (String token : tokens) {
            int index = value.indexOf(token);

            if (index >= 0
                    && (result < 0 || index < result)) {
                result = index;
            }
        }

        return result;
    }
}
