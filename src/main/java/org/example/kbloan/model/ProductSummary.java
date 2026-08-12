package org.example.kbloan.model;

public record ProductSummary(
        String productCode,
        String productName,
        String description,
        String joinChannel,
        String limitText,
        String detailUrl,
        String discoveryMethod
) {
}
