package org.example.kbloan.parser;

import org.example.kbloan.model.ProductSummary;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class TargetProductFilter {

    private final List<String> includeKeywords;

    public TargetProductFilter() {
        String property = System.getProperty(
                "kb.targetKeywords",
                "신용대출"
        );

        this.includeKeywords = Arrays.stream(property.split(","))
                .map(String::trim)
                .filter(keyword -> !keyword.isBlank())
                .toList();
    }

    public boolean isTarget(ProductSummary product) {
        String text = (
                product.productName()
                        + " "
                        + product.description()
        ).toLowerCase(Locale.ROOT);

        boolean included = includeKeywords.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(text::contains);

        if (!included) {
            return false;
        }

        return !text.contains("주택담보")
                && !text.contains("아파트담보")
                && !text.contains("전세대출")
                && !text.contains("자동차대출")
                && !text.contains("예부적금")
                && !text.contains("예적금")
                && !text.contains("청약")
                && !text.contains("보금자리론")
                && !text.contains("주택연금")
                && !text.contains("집단");
    }
}
