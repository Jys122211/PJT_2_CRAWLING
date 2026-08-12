package org.example.kbloan.parser;

import org.example.kbloan.model.PreferentialItem;
import org.example.kbloan.util.TextUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PreferentialParser {

    private static final Pattern RATE_PATTERN =
            Pattern.compile(
                    "(?:최고\\s*)?"
                            + "(?:연\\s*)?"
                            + "([0-9]+(?:\\.[0-9]+)?)"
                            + "\\s*%(?:p)?",
                    Pattern.CASE_INSENSITIVE
            );

    public List<PreferentialItem> parse(
            String preferentialRawText
    ) {
        if (preferentialRawText == null
                || preferentialRawText.isBlank()) {
            return List.of();
        }

        List<MutablePreference> parsed =
                new ArrayList<>();

        String currentGroup = "";
        MutablePreference lastItem = null;
        String pendingConditionName = "";

        for (String rawLine
                : preferentialRawText.split("\\R")) {

            String line = TextUtils.oneLine(rawLine);

            if (line.isBlank()) {
                continue;
            }

            Matcher matcher = RATE_PATTERN.matcher(line);

            if (matcher.find()) {
                BigDecimal discount =
                        new BigDecimal(matcher.group(1));

                boolean maximumCap =
                        line.contains("최고");

                String beforeRate =
                        line.substring(0, matcher.start());

                String afterRate =
                        line.substring(matcher.end());

                String conditionName =
                        cleanConditionName(beforeRate);

                if (conditionName.isBlank()) {
                    conditionName =
                            cleanConditionName(
                                    pendingConditionName
                            );
                }

                if (conditionName.isBlank()) {
                    conditionName = "우대금리";
                }

                if (conditionName.contains("실적 연동 우대")
                        || conditionName.contains("실적연동 우대")) {
                    currentGroup = conditionName;
                } else if (line.startsWith("☞")
                        && !conditionName.contains("실적")) {
                    currentGroup = "기타 우대";
                }

                MutablePreference item =
                        new MutablePreference();

                item.groupName = maximumCap
                        ? conditionName
                        : currentGroup;

                item.conditionName = conditionName;
                item.conditionDetail =
                        cleanDetail(afterRate);

                item.discount = discount;
                item.maximumCap = maximumCap;
                item.autoCheckable =
                        isAutoCheckable(conditionName);

                item.rawText = line;

                parsed.add(item);
                lastItem = item;
                pendingConditionName = "";
                continue;
            }

            if (looksLikeConditionName(line)) {
                pendingConditionName = line;
            }

            if (lastItem != null
                    && looksLikeDetail(line)) {
                lastItem.conditionDetail =
                        append(
                                lastItem.conditionDetail,
                                cleanDetail(line)
                        );

                lastItem.rawText =
                        append(lastItem.rawText, line);
            }
        }

        List<PreferentialItem> result =
                new ArrayList<>();

        Set<String> unique = new LinkedHashSet<>();

        for (MutablePreference item : parsed) {
            String key = item.groupName
                    + "|"
                    + item.conditionName
                    + "|"
                    + item.discount
                    + "|"
                    + item.maximumCap;

            if (!unique.add(key)) {
                continue;
            }

            result.add(new PreferentialItem(
                    item.groupName,
                    item.conditionName,
                    item.conditionDetail,
                    item.discount,
                    item.maximumCap,
                    item.autoCheckable,
                    item.rawText
            ));
        }

        return result;
    }

    private String cleanConditionName(String value) {
        String cleaned = TextUtils.oneLine(value)
                .replaceFirst(
                        "^[ㅇ☞※\\-•·* ]+",
                        ""
                )
                .replaceFirst(
                        "^\\(?\\d+\\)?\\s*",
                        ""
                )
                .replaceFirst(
                        "^우대금리\\s*",
                        ""
                )
                .replaceFirst(
                        "[:：\\- ]+$",
                        ""
                )
                .trim();

        return cleaned;
    }

    private String cleanDetail(String value) {
        return TextUtils.oneLine(value)
                .replaceFirst(
                        "^[()\\-:：, ]+",
                        ""
                )
                .replaceFirst(
                        "[() ]+$",
                        ""
                )
                .trim();
    }

    private boolean looksLikeConditionName(String line) {
        return line.startsWith("ㅇ")
                || line.startsWith("☞")
                || line.contains("우대")
                || line.contains("금리할인");
    }

    private boolean looksLikeDetail(String line) {
        return line.startsWith("-")
                || line.startsWith("※")
                || line.startsWith("(")
                || line.contains("이용시")
                || line.contains("이체시")
                || line.contains("보유시")
                || line.contains("경우에 한함")
                || line.contains("재산정");
    }

    private boolean isAutoCheckable(String name) {
        String text = name.toLowerCase(Locale.ROOT);

        return text.contains("전자계약")
                || text.contains("자동이체")
                || text.contains("급여")
                || text.contains("연금이체")
                || text.contains("신용카드")
                || text.contains("예금")
                || text.contains("스타뱅킹")
                || text.contains("청약");
    }

    private String append(
            String current,
            String addition
    ) {
        if (addition == null || addition.isBlank()) {
            return current == null ? "" : current;
        }

        if (current == null || current.isBlank()) {
            return addition;
        }

        return current + " " + addition;
    }

    private static final class MutablePreference {
        private String groupName = "";
        private String conditionName = "";
        private String conditionDetail = "";
        private BigDecimal discount;
        private boolean maximumCap;
        private boolean autoCheckable;
        private String rawText = "";
    }
}
