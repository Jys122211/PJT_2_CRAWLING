package org.example.kbloan.collector;

import org.example.kbloan.parser.ProductHeaderParser;
import org.example.kbloan.util.PropertySource;

import java.util.ArrayList;
import java.util.List;

/**
 * KB 사이트 주소 설정.
 *
 * 코드에 기본값을 두지 않는다. 설정이 없으면 조용히 엉뚱한 주소를 긁는 대신
 * 시작 단계에서 멈추는 편이 낫다. missingKeys() 로 무엇이 빠졌는지 알려준다.
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
    public static CrawlUrlSettings fromSystemProperties() {
        PropertySource source = PropertySource.load();

        return new CrawlUrlSettings(
                source.value("kb.listUrl", "KB_LIST_URL", null),
                // 빈 값은 "목록 모드로 돌려라" 라는 뜻이므로 그대로 살려야 한다.
                // 일반 value() 로 읽으면 빈 값을 "설정 없음" 으로 보고 넘어가 의도가 뒤집힌다.
                source.valueAllowingBlank("kb.detailUrl", "KB_DETAIL_URL", null),
                source.value("kb.detailUrlPrefix", "KB_DETAIL_URL_PREFIX", null),
                source.value("kb.newDetailUrlPrefix", "KB_NEW_DETAIL_URL_PREFIX", null)
        );
    }

    /**
     * 빠진 설정 키 목록. 비어 있으면 정상이다.
     *
     * kb.detailUrl 하나만 있으면 그 주소로 바로 가면 되므로 다른 설정이 필요 없다.
     * 목록 모드(kb.detailUrl 을 비운 경우)일 때만 목록 주소와 접두사가 필요하다.
     * 상품번호로 상세 주소를 조립해야 하기 때문이다.
     */
    public List<String> missingKeys() {
        if (hasTargetDetailUrl()) {
            return List.of();
        }

        List<String> missing = new ArrayList<>();

        if (isBlank(listUrl)) {
            missing.add("kb.listUrl");
        }
        if (isBlank(detailUrlPrefix)) {
            missing.add("kb.detailUrlPrefix");
        }
        if (isBlank(newDetailUrlPrefix)) {
            missing.add("kb.newDetailUrlPrefix");
        }

        return missing;
    }

    /** 상품번호로 주소를 조립할 수 있는지. 목록 모드에서만 참이어야 한다. */
    public boolean hasDetailPrefixes() {
        return !isBlank(detailUrlPrefix) && !isBlank(newDetailUrlPrefix);
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
        return !isBlank(detailUrl);
    }

    /**
     * 지정된 상세 주소에서 뽑아낸 상품번호. 없으면 null.
     * 브라우저에서 복사한 URL 을 그대로 붙여넣어도 되게 하려는 것이다.
     */
    public String targetProductCode() {
        return ProductHeaderParser.productCodeFromUrl(detailUrl);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
