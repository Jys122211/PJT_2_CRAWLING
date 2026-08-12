package org.example.kbloan.collector;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.example.kbloan.model.*;
import org.example.kbloan.parser.*;
import org.example.kbloan.util.DetailUrlResolver;

import java.time.Instant;
import java.util.*;

public final class KbLoanCollector {

    private final BrowserContext context;

    private final CrawlUrlSettings urlSettings;

    private final ProductListParser listParser =
            new ProductListParser();

    private final TargetProductFilter targetFilter =
            new TargetProductFilter();

    private final LoanDetailParser detailParser =
            new LoanDetailParser();

    private final ProductHeaderParser headerParser =
            new ProductHeaderParser();

    private final ConditionParser conditionParser =
            new ConditionParser();

    public KbLoanCollector(
            BrowserContext context,
            CrawlUrlSettings urlSettings
    ) {
        this.context = context;
        this.urlSettings = urlSettings;
    }

    public LoanCollectionResult collect()
            throws Exception {

        List<String> collectionWarnings =
                new ArrayList<>();

        List<ProductSummary> discovered;
        List<ProductSummary> targets;
        String sourceUrl;
        CollectMode collectMode;

        if (urlSettings.hasTargetDetailUrl()) {
            // 상품 신원은 지정된 주소의 페이지에서 읽는다.
            // 여기서는 상품코드만 알고 있고, 나머지는 상세 수집 후
            // ProductHeaderParser 가 페이지에서 읽어 채운다.
            // (예전에는 KnownProductCatalog 의 하드코딩 값을 그대로 붙여서,
            //  주소만 다른 상품으로 바꾸면 엉뚱한 상품에 금리가 저장됐다.)
            String productCode = urlSettings.targetProductCode();

            ProductSummary directTarget = new ProductSummary(
                    productCode == null ? "" : productCode,
                    "",   // 상품명·설명·채널·한도는 전부 페이지에서 읽는다
                    "",
                    "",
                    "",
                    urlSettings.detailUrl(),
                    "DIRECT_DETAIL_URL"
            );

            discovered = List.of(directTarget);
            targets = discovered;
            sourceUrl = urlSettings.detailUrl();
            collectMode = CollectMode.DIRECT_URL;
        } else {
            Page listPage = context.newPage();
            String listHtml;

            try {
                navigate(listPage, urlSettings.listUrl());
                listHtml = listPage.content();
            } finally {
                listPage.close();
            }

            discovered = listParser.parse(listHtml, urlSettings.listUrl());
            targets = discovered.stream()
                    .filter(targetFilter::isTarget)
                    .toList();

            if (targets.isEmpty()) {
                collectionWarnings.add(
                        "목록 DOM에서 대상 상품과 상품코드를 "
                                + "찾지 못해 알려진 상품 목록을 사용했습니다."
                );
                targets = KnownProductCatalog.creditLoanFallback(urlSettings);
                collectMode = CollectMode.FALLBACK_CATALOG;
            } else {
                targets = mergeFallbackForMissingKnownProducts(targets);
                collectMode = CollectMode.LIST_DISCOVERY;
            }
            sourceUrl = urlSettings.listUrl();
        }

        List<LoanProductDetail> products =
                new ArrayList<>();

        List<FailedTarget> failedTargets =
                new ArrayList<>();

        for (ProductSummary target : targets) {
            try {
                CollectedDetail collected =
                        collectDetail(target);

                LoanProductDetail detail = detailParser.parse(
                        collected.product(),
                        collected.html(),
                        collected.visibleText()
                );

                // 우대·자격조건은 숨은 탭에 있어 화면 텍스트에는 안 나온다.
                // HTML 을 재료로 따로 읽는다.
                products.add(detail.withConditions(
                        conditionParser.parsePreferential(collected.html()),
                        conditionParser.parseQualification(collected.html())
                ));
            } catch (Exception e) {
                collectionWarnings.add(
                        target.productName()
                                + " 상세 수집 실패: "
                                + e.getMessage()
                );

                // 실패한 대상도 넘겨야 한다. 목록에서 본 상품임에는 변함이 없으므로,
                // 여기서 누락시키면 소실된 상품으로 오인해 논리삭제하게 된다.
                failedTargets.add(
                        new FailedTarget(target, e.getMessage())
                );
            }
        }

        return new LoanCollectionResult(
                sourceUrl,
                collectMode,
                Instant.now().toString(),
                discovered.size(),
                targets.size(),
                List.copyOf(collectionWarnings),
                List.copyOf(products),
                List.copyOf(failedTargets)
        );
    }

    private CollectedDetail collectDetail(
            ProductSummary target
    ) {
        List<String> attempted = new ArrayList<>();

        for (String url
                : DetailUrlResolver.candidates(target, urlSettings)) {

            attempted.add(url);

            Page page = context.newPage();

            try {
                navigate(page, url);

                String html = page.content();
                String visibleText =
                        page.locator("body").innerText();

                if (!detailParser.looksLikeDetailPage(
                        html,
                        visibleText
                )) {
                    continue;
                }

                ProductSummary resolved =
                        new ProductSummary(
                                target.productCode(),
                                target.productName(),
                                target.description(),
                                target.joinChannel(),
                                target.limitText(),
                                url,
                                target.discoveryMethod()
                        );

                // 실제로 연 페이지에 적힌 상품명·한도로 채운다.
                // 목록에서 온 값이 이미 있으면 그대로 두고, 비어 있는 것만 메운다.
                return new CollectedDetail(
                        headerParser.enrich(resolved, visibleText),
                        html,
                        visibleText
                );
            } finally {
                page.close();
            }
        }

        throw new IllegalStateException(
                "유효한 상세페이지를 찾지 못했습니다. 시도 URL="
                        + attempted
        );
    }

    private void navigate(Page page, String url) {
        page.navigate(
                url,
                new Page.NavigateOptions()
                        .setWaitUntil(
                                WaitUntilState.DOMCONTENTLOADED
                        )
                        .setTimeout(60_000)
        );

        try {
            page.waitForLoadState(
                    LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions()
                            .setTimeout(10_000)
            );
        } catch (PlaywrightException ignored) {
            // 분석 스크립트 등으로 networkidle에 도달하지 않아도
            // 본문 텍스트가 있으면 수집을 계속합니다.
        }

        try {
            page.waitForFunction(
                    "() => document.body && "
                            + "(document.body.innerText.includes('대출신청자격') "
                            + "|| document.body.innerText.includes('금리 및 이율'))",
                    null,
                    new Page.WaitForFunctionOptions()
                            .setTimeout(15_000)
            );
        } catch (PlaywrightException ignored) {
            // 일부 상품은 제목 표현이 다를 수 있으므로
            // 이후 본문 검증 단계에서 다시 확인합니다.
        }

        page.waitForTimeout(1_000);
    }

    private List<ProductSummary>
    mergeFallbackForMissingKnownProducts(
            List<ProductSummary> discoveredTargets
    ) {
        Map<String, ProductSummary> merged =
                new LinkedHashMap<>();

        for (ProductSummary product : discoveredTargets) {
            String key = product.productCode().isBlank()
                    ? product.productName()
                    : product.productCode();

            merged.put(key, product);
        }

        for (ProductSummary fallback
                : KnownProductCatalog.creditLoanFallback(urlSettings)) {

            boolean exists = merged.values().stream()
                    .anyMatch(product ->
                            product.productCode().equals(
                                    fallback.productCode()
                            )
                            || product.productName().contains(
                                    fallback.productName()
                            )
                    );

            if (!exists) {
                merged.put(
                        fallback.productCode(),
                        fallback
                );
            }
        }

        return new ArrayList<>(merged.values());
    }

    private record CollectedDetail(
            ProductSummary product,
            String html,
            String visibleText
    ) {
    }
}
