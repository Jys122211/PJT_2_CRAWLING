package org.example.kbloan.collector;

import org.example.kbloan.parser.ProductHeaderParser;
import org.example.kbloan.util.PropertySource;

/**
 * KB 사이트 주소 설정.
 *
 * 예전에는 이 값들이 자바 코드 네 곳에 흩어져 박혀 있었다. KB 가 URL 체계를
 * 바꾸면 코드를 고치고 재빌드해야 했다. 이제 application.properties 만 고치면 된다.
 *
 * detailUrl 에 상세 주소를 통째로 넣으면 그 상품 하나만 본다(DIRECT_URL).
 * 브라우저 주소창에서 복사한 URL 을 그대로 붙여넣으면 되고,
 * 상품번호(prcode)는 크롤러가 URL 에서 뽑아 쓴다.
 * 비워두면 목록 페이지부터 훑는다(LIST_DISCOVERY).
 */
public record CrawlUrlSettings(
        String listUrl,
        String detailUrl,
        String detailUrlPrefix,
        String newDetailUrlPrefix
) {
    private static final String DEFAULT_LIST_URL =
            "https://obank.kbstar.com/quics?page=C103429";

    private static final String DEFAULT_DETAIL_PREFIX =
            "https://obank.kbstar.com/quics?QSL=F"
                    + "&cc=b104363%3Ab104516"
                    + "&isNew=N"
                    + "&page=C103429"
                    + "&prcode=";

    private static final String DEFAULT_NEW_DETAIL_PREFIX =
            "https://obank.kbstar.com/quics?QSL=F"
                    + "&cc=b104363%3Ab104516"
                    + "&isNew=Y"
                    + "&page=C103429"
                    + "&prcode=";

    /** 설정을 지웠을 때도 지금까지와 같은 상품을 긁도록 기본값을 둔다. */
    private static final String DEFAULT_DETAIL_URL =
            DEFAULT_DETAIL_PREFIX + "LN20001397";

    public static CrawlUrlSettings fromSystemProperties() {
        PropertySource source = PropertySource.load();

        return new CrawlUrlSettings(
                source.value(
                        "kb.listUrl", "KB_LIST_URL", DEFAULT_LIST_URL
                ),
                source.valueAllowingBlank(
                        "kb.detailUrl", "KB_DETAIL_URL", DEFAULT_DETAIL_URL
                ),
                source.value(
                        "kb.detailUrlPrefix",
                        "KB_DETAIL_URL_PREFIX",
                        DEFAULT_DETAIL_PREFIX
                ),
                source.value(
                        "kb.newDetailUrlPrefix",
                        "KB_NEW_DETAIL_URL_PREFIX",
                        DEFAULT_NEW_DETAIL_PREFIX
                )
        );
    }

    /** 상품코드로 상세 주소를 만든다. */
    public String detailUrlFor(String productCode) {
        return detailUrlPrefix + productCode;
    }

    public String newDetailUrlFor(String productCode) {
        return newDetailUrlPrefix + productCode;
    }

    /** 특정 상품 하나만 볼지, 목록 전체를 훑을지. */
    public boolean hasTargetDetailUrl() {
        return detailUrl != null && !detailUrl.isBlank();
    }

    /**
     * 지정된 상세 주소에서 뽑아낸 상품번호. 없으면 null.
     * 브라우저에서 복사한 URL 을 그대로 붙여넣어도 되게 하려는 것이다.
     */
    public String targetProductCode() {
        return ProductHeaderParser.productCodeFromUrl(detailUrl);
    }
}
