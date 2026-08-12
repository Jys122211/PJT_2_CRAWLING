package org.example.kbloan.parser;

import org.example.kbloan.model.ProductSummary;
import org.example.kbloan.util.TextUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProductListParser {

    private static final Pattern PRODUCT_CODE =
            Pattern.compile("\\bLN\\d{8}\\b");

    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("최고\\s+(.+?)(?=장바구니|비교하기|신청하기|$)");

    public List<ProductSummary> parse(String html, String baseUrl) {
        Document document = Jsoup.parse(html, baseUrl);

        List<Element> cards = findSmallestProductCards(document);
        Map<String, ProductSummary> unique = new LinkedHashMap<>();

        for (Element card : cards) {
            ProductSummary summary = parseCard(card);
            if (summary == null || summary.productName().isBlank()) {
                continue;
            }

            String key = !summary.productCode().isBlank()
                    ? summary.productCode()
                    : summary.productName();

            unique.putIfAbsent(key, summary);
        }

        return new ArrayList<>(unique.values());
    }

    private List<Element> findSmallestProductCards(Document document) {
        List<Element> candidates = new ArrayList<>();

        for (Element element : document.select("li, article, div")) {
            String text = TextUtils.oneLine(element.text());

            if (!looksLikeProductCard(text)) {
                continue;
            }

            boolean childIsAlsoCard = element.children().stream()
                    .anyMatch(child ->
                            looksLikeProductCard(
                                    TextUtils.oneLine(child.text())
                            )
                    );

            if (!childIsAlsoCard) {
                candidates.add(element);
            }
        }

        return candidates;
    }

    private boolean looksLikeProductCard(String text) {
        return text.contains("대출")
                && text.contains("가입가능채널")
                && (text.contains("장바구니")
                    || text.contains("비교하기"))
                && text.length() >= 20
                && text.length() <= 1500;
    }

    private ProductSummary parseCard(Element card) {
        String fullText = TextUtils.oneLine(card.text());

        String productName = findProductName(card);
        if (productName.isBlank()) {
            return null;
        }

        String productCode = findProductCode(card);
        String detailUrl = findDetailUrl(card);

        String description = extractDescription(
                fullText,
                productName
        );

        String channel = extractBetween(
                fullText,
                "가입가능채널",
                "최고"
        );

        String limit = "";
        Matcher limitMatcher = LIMIT_PATTERN.matcher(fullText);
        if (limitMatcher.find()) {
            limit = TextUtils.oneLine(limitMatcher.group(1));
        }

        return new ProductSummary(
                productCode,
                productName,
                description,
                channel,
                limit,
                detailUrl,
                productCode.isBlank()
                        ? "LIST_TEXT"
                        : "LIST_DOM"
        );
    }

    private String findProductName(Element card) {
        Elements titleCandidates = card.select(
                "h2, h3, h4, h5, strong, dt, a, button"
        );

        for (Element candidate : titleCandidates) {
            String text = TextUtils.oneLine(candidate.text());

            if (text.contains("대출")
                    && !text.equals("대출")
                    && !text.contains("장바구니")
                    && !text.contains("비교하기")
                    && text.length() <= 120) {
                return text;
            }
        }

        String text = TextUtils.oneLine(card.text());
        int channelIndex = text.indexOf("가입가능채널");
        if (channelIndex > 0) {
            String before = text.substring(0, channelIndex).trim();
            return before.length() <= 120
                    ? before
                    : before.substring(0, 120).trim();
        }

        return "";
    }

    private String findProductCode(Element card) {
        Element current = card;

        for (int depth = 0;
             current != null && depth < 4;
             depth++, current = current.parent()) {

            String source = current.outerHtml();
            Matcher matcher = PRODUCT_CODE.matcher(source);

            if (matcher.find()) {
                return matcher.group();
            }
        }

        return "";
    }

    private String findDetailUrl(Element card) {
        for (Element link : card.select("a[href]")) {
            String href = link.absUrl("href");

            if (href.startsWith("http")
                    && href.contains("kbstar.com")
                    && href.contains("quics")) {
                return href;
            }
        }

        for (Element element : card.getAllElements()) {
            for (Attribute attribute : element.attributes()) {
                String value = attribute.getValue();

                if (value.startsWith("http")
                        && value.contains("kbstar.com")
                        && value.contains("quics")) {
                    return value;
                }
            }
        }

        return "";
    }

    private String extractDescription(
            String fullText,
            String productName
    ) {
        int start = fullText.indexOf(productName);
        int end = fullText.indexOf("가입가능채널");

        if (start < 0 || end <= start) {
            return "";
        }

        return fullText
                .substring(start + productName.length(), end)
                .trim();
    }

    private String extractBetween(
            String text,
            String startToken,
            String endToken
    ) {
        int start = text.indexOf(startToken);
        if (start < 0) {
            return "";
        }

        start += startToken.length();

        int end = text.indexOf(endToken, start);
        if (end < 0) {
            end = text.length();
        }

        return text.substring(start, end).trim();
    }
}
