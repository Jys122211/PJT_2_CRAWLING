package org.example.kbloan.util;

import org.example.kbloan.collector.CrawlUrlSettings;
import org.example.kbloan.model.ProductSummary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 상품 하나의 상세 주소 후보를 만든다.
 *
 * KB 는 같은 상품이라도 isNew 파라미터에 따라 다른 화면을 주는 경우가 있어,
 * 두 벌을 만들어 두고 먼저 열리는 쪽을 쓴다.
 * 주소 형식은 CrawlUrlSettings 가 들고 있다(application.properties 로 교체 가능).
 */
public final class DetailUrlResolver {

    private DetailUrlResolver() {
    }

    public static List<String> candidates(
            ProductSummary product,
            CrawlUrlSettings urlSettings
    ) {
        Set<String> urls = new LinkedHashSet<>();

        if (product.detailUrl() != null
                && product.detailUrl().startsWith("http")) {
            urls.add(product.detailUrl());
        }

        if (product.productCode() != null
                && !product.productCode().isBlank()) {
            urls.add(urlSettings.detailUrlFor(product.productCode()));
            urls.add(urlSettings.newDetailUrlFor(product.productCode()));
        }

        return new ArrayList<>(urls);
    }
}
