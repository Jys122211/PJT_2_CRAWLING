package org.example.kbloan.parser;

import org.example.kbloan.util.TextUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.List;
import java.util.Set;

public final class SectionExtractor {

    private static final String HEADING_SELECTOR =
            "h1, h2, h3, h4, h5, h6, dt, th, strong";

    private static final Set<String> GLOBAL_STOP_TITLES = Set.of(
            "상품내용의 변경에 관한 사항",
            "필요서류",
            "유의사항",
            "기타",
            "관련서식"
    );

    public String extract(
            Document document,
            List<String> startAliases,
            List<String> endAliases
    ) {
        Element start = findHeading(document, startAliases);
        if (start == null) {
            return "";
        }

        if (start.tagName().equals("dt")) {
            return collectDefinitionList(start);
        }

        if (start.tagName().equals("th")) {
            Element row = start.closest("tr");
            if (row != null) {
                Elements cells = row.select("td");
                if (!cells.isEmpty()) {
                    return TextUtils.normalize(cells.text());
                }
            }
        }

        String siblingText = collectFollowingSiblings(
                start,
                endAliases
        );

        if (!siblingText.isBlank()) {
            return siblingText;
        }

        Element parent = start.parent();
        if (parent != null) {
            String parentText = TextUtils.normalize(parent.text());
            String titleText = TextUtils.normalize(start.text());

            if (parentText.startsWith(titleText)) {
                return parentText.substring(
                        titleText.length()
                ).trim();
            }
        }

        return "";
    }

    private Element findHeading(
            Document document,
            List<String> aliases
    ) {
        for (Element element : document.select(HEADING_SELECTOR)) {
            String text = TextUtils.oneLine(element.text());

            for (String alias : aliases) {
                if (text.equals(alias)
                        || text.startsWith(alias + " ")
                        || text.contains(alias)) {
                    return element;
                }
            }
        }

        return null;
    }

    private String collectDefinitionList(Element start) {
        StringBuilder result = new StringBuilder();
        Element current = start.nextElementSibling();

        while (current != null
                && !current.tagName().equals("dt")) {
            result.append(current.text()).append('\n');
            current = current.nextElementSibling();
        }

        return TextUtils.normalize(result.toString());
    }

    private String collectFollowingSiblings(
            Element start,
            List<String> endAliases
    ) {
        StringBuilder result = new StringBuilder();
        Element current = start.nextElementSibling();

        while (current != null) {
            String text = TextUtils.oneLine(current.text());

            if (isStopHeading(current, text, endAliases)) {
                break;
            }

            result.append(current.text()).append('\n');
            current = current.nextElementSibling();
        }

        return TextUtils.normalize(result.toString());
    }

    private boolean isStopHeading(
            Element element,
            String text,
            List<String> endAliases
    ) {
        boolean headingTag = element.is(HEADING_SELECTOR);

        if (!headingTag) {
            return false;
        }

        if (GLOBAL_STOP_TITLES.stream()
                .anyMatch(text::contains)) {
            return true;
        }

        return endAliases.stream().anyMatch(text::contains);
    }
}
