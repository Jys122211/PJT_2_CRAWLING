package org.example.kbloan.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextUtils {

    private static final Pattern NUMBER =
            Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private TextUtils() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    public static String oneLine(String value) {
        return normalize(value).replace('\n', ' ');
    }

    public static BigDecimal firstDecimal(String value) {
        if (value == null) {
            return null;
        }

        String cleaned = value
                .replace(",", "")
                .replace("%p", "")
                .replace("%", "");

        Matcher matcher = NUMBER.matcher(cleaned);
        if (!matcher.find()) {
            return null;
        }

        try {
            return new BigDecimal(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String normalizedHeader(String value) {
        return oneLine(value)
                .replace("(", "")
                .replace(")", "")
                .replace("연", "")
                .replace("%p", "")
                .replace("%", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }
}
