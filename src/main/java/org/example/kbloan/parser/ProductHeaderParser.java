package org.example.kbloan.parser;

import org.example.kbloan.model.ProductSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상세 페이지 머리말에서 상품 신원을 읽는다.
 *
 * 예전에는 상세 URL 을 직접 지정하면 상품명·한도를 KnownProductCatalog(코드에 박아둔 값)
 * 에서 가져왔다. 그래서 URL 만 다른 상품으로 바꾸면 엉뚱한 상품 이름으로 저장됐다.
 * 주소로 갔으면 그 페이지에 적힌 것을 읽어야 한다.
 *
 * KB 상세 페이지는 이런 순서로 나온다.
 *   ...
 *   '직장인'대상 대면 통합 신용대출     ← 설명
 *   KB 신용대출                        ← 상품명
 *   가입가능채널                        ← 기준점
 *   영업점                             ← 가입채널
 *   ...
 *   대출한도
 *   최고
 *   3.5억원                            ← 한도
 */
public final class ProductHeaderParser {

    private static final String CHANNEL_ANCHOR = "가입가능채널";
    private static final String LIMIT_ANCHOR = "대출한도";

    /** 한도 문구를 찾을 때 기준점에서 몇 줄까지 훑을지. */
    private static final int LIMIT_SCAN_LINES = 6;

    private static final Pattern PRODUCT_CODE =
            Pattern.compile("prcode=([A-Za-z0-9]+)");

    private static final Pattern MONEY =
            Pattern.compile("[0-9][0-9,.]*\\s*(억원|만원|원)");

    /**
     * @return 페이지에서 읽어낸 상품 정보. 기준점을 못 찾으면 null 을 담은 필드가 생긴다.
     */
    public ParsedHeader parse(String visibleText) {
        List<String> lines = nonBlankLines(visibleText);

        int channelIndex = indexOf(lines, CHANNEL_ANCHOR);

        String productName = channelIndex > 0
                ? lines.get(channelIndex - 1)
                : null;

        String description = channelIndex > 1
                ? lines.get(channelIndex - 2)
                : null;

        String joinChannel = channelIndex >= 0
                && channelIndex + 1 < lines.size()
                ? lines.get(channelIndex + 1)
                : null;

        return new ParsedHeader(
                productName,
                description,
                joinChannel,
                limitText(lines)
        );
    }

    /** URL 의 prcode 파라미터. 없으면 null. */
    public static String productCodeFromUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = PRODUCT_CODE.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 비어 있는 항목만 페이지에서 읽은 값으로 메운다.
     *
     * 이미 값이 있으면(목록 페이지에서 읽어온 경우) 건드리지 않는다.
     * 상세 URL 을 직접 지정한 경우에만 항목이 비어 있으므로, 그때만 채워진다.
     */
    public ProductSummary enrich(ProductSummary base, String visibleText) {
        ParsedHeader header = parse(visibleText);

        String productCode = firstNonBlank(
                base.productCode(),
                productCodeFromUrl(base.detailUrl())
        );

        // 페이지 구조가 바뀌어 상품명을 못 읽으면 상품코드라도 남긴다.
        // 빈 이름으로 저장하면 정규화 키가 빈 문자열이 되어 엉뚱한 상품과 묶인다.
        String productName = firstNonBlank(
                firstNonBlank(base.productName(), header.productName()),
                productCode
        );

        return new ProductSummary(
                productCode,
                productName,
                firstNonBlank(base.description(), header.description()),
                firstNonBlank(base.joinChannel(), header.joinChannel()),
                firstNonBlank(base.limitText(), header.limitText()),
                base.detailUrl(),
                base.discoveryMethod()
        );
    }

    private String limitText(List<String> lines) {
        int limitIndex = indexOf(lines, LIMIT_ANCHOR);
        if (limitIndex < 0) {
            return null;
        }

        int end = Math.min(lines.size(), limitIndex + 1 + LIMIT_SCAN_LINES);
        for (int i = limitIndex + 1; i < end; i++) {
            String line = lines.get(i);
            if (MONEY.matcher(line).find()) {
                // "최고" 같은 수식어가 앞줄에 따로 있으므로 붙여서 돌려준다.
                String previous = lines.get(i - 1);
                return previous.equals(LIMIT_ANCHOR)
                        ? line
                        : previous + " " + line;
            }
        }
        return null;
    }

    private static List<String> nonBlankLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static int indexOf(List<String> lines, String anchor) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals(anchor)) {
                return i;
            }
        }
        return -1;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    public record ParsedHeader(
            String productName,
            String description,
            String joinChannel,
            String limitText
    ) {
    }
}
