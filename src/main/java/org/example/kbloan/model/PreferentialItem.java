package org.example.kbloan.model;

import java.math.BigDecimal;

public record PreferentialItem(
        String groupName,
        String conditionName,
        String conditionDetail,
        BigDecimal discountPercentPoint,
        boolean maximumCap,
        boolean autoCheckable,
        String rawText
) {
}
