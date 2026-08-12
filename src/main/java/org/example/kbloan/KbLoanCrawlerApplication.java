package org.example.kbloan;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.example.kbloan.collector.CrawlUrlSettings;
import org.example.kbloan.collector.KbLoanCollector;
import org.example.kbloan.model.FailedTarget;
import org.example.kbloan.model.FinlifeGradeRates;
import org.example.kbloan.model.LoanCollectionResult;
import org.example.kbloan.model.LoanProductDetail;
import org.example.kbloan.model.ProductSnapshot;
import org.example.kbloan.persistence.CrawlBatchRepository;
import org.example.kbloan.persistence.CrawlHistoryRepository;
import org.example.kbloan.persistence.CreditLoanRateSynchronizer;
import org.example.kbloan.persistence.DatabaseSettings;
import org.example.kbloan.persistence.ProductSnapshotFactory;
import org.example.kbloan.util.PropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 저장 순서가 이 클래스의 핵심이다.
 *
 *   ① 배치 시작 기록  → 즉시 커밋. 이후 죽어도 RUNNING 으로 흔적이 남는다
 *   ② 크롤링
 *   ③ 이력 저장       → 커밋. "이날 이런 값이 있었다"는 사실은 무조건 남긴다
 *   ④ 라이브 반영     → 여기만 all-or-nothing. 실패해도 ①②③은 살아있다
 *   ⑤ 배치 마무리     → 성공이든 실패든 반드시 기록
 */
public final class KbLoanCrawlerApplication {

    private static final String BANK_NAME = "KB국민은행";
    private static final String PRODUCT_CATEGORY = "CREDIT_LOAN";

    private KbLoanCrawlerApplication() {
    }

    public static void main(String[] args) {
        boolean headless = Boolean.parseBoolean(
                System.getProperty("kb.headless", "true")
        );

        DatabaseSettings databaseSettings = DatabaseSettings.fromSystemProperties();
        if (!databaseSettings.enabled()) {
            System.err.println(
                    "DB 설정이 없습니다. application.properties의 "
                            + "jdbc.url, jdbc.username, jdbc.password를 확인해주세요."
            );
            System.exit(1);
            return;
        }

        CrawlUrlSettings urlSettings = CrawlUrlSettings.fromSystemProperties();

        // 주소 기본값을 코드에 두지 않는다. 설정이 빠지면 엉뚱한 주소를 긁는 대신
        // 여기서 멈춰서 무엇이 없는지 알려준다.
        List<String> missingUrlKeys = urlSettings.missingKeys();
        if (!missingUrlKeys.isEmpty()) {
            System.err.println(
                    "KB 주소 설정이 없습니다. application.properties에 "
                            + String.join(", ", missingUrlKeys)
                            + " 를 채워주세요."
            );
            System.exit(1);
            return;
        }

        System.out.printf(
                "수집 대상 주소: %s%n",
                urlSettings.hasTargetDetailUrl()
                        ? "상품 " + urlSettings.targetProductCode()
                                + " 하나 · " + urlSettings.detailUrl()
                        : "목록 전체 · " + urlSettings.listUrl()
        );

        FinlifeGradeRates finlifeRates = FinlifeGradeRates.fixedJune2026();
        System.out.printf(
                "등급별 금리 비율 적용: 고정 공시월 %s%n",
                finlifeRates.disclosureMonth()
        );

        CrawlBatchRepository batchRepository =
                new CrawlBatchRepository(databaseSettings);
        CrawlHistoryRepository historyRepository =
                new CrawlHistoryRepository(databaseSettings);
        CreditLoanRateSynchronizer synchronizer =
                new CreditLoanRateSynchronizer(databaseSettings);
        ProductSnapshotFactory snapshotFactory = new ProductSnapshotFactory();

        CrawlBatchRepository.Batch batch = null;

        try {
            // ① 크롤링 시작 전에 배치 행을 만들고 바로 커밋한다.
            batch = batchRepository.start(new CrawlBatchRepository.BatchStart(
                    BANK_NAME,
                    PRODUCT_CATEGORY,
                    executedBy(),
                    disclosureMonthForColumn(finlifeRates.disclosureMonth())
            ));
            System.out.println("배치 시작: " + batch.uuid());

            // ② 크롤링
            LoanCollectionResult result = collect(headless, urlSettings);

            // ③ 스냅샷으로 변환 → 라이브 상품 ID 연결 → 이력 저장
            List<ProductSnapshot> snapshots = new ArrayList<>();
            for (LoanProductDetail product : result.products()) {
                snapshots.add(snapshotFactory.create(
                        product, Optional.of(finlifeRates)
                ));
            }
            // 상세 수집에 실패한 상품도 스냅샷으로 남긴다.
            // 이력에 FAILED 로 기록되고, 무엇보다 "이번에도 목록에서 봤다"는
            // 사실이 남아 소실로 오인되지 않는다.
            for (FailedTarget failed : result.failedTargets()) {
                snapshots.add(snapshotFactory.createFailed(
                        failed.product().productName(),
                        failed.product().detailUrl(),
                        failed.reason()
                ));
            }
            snapshots = synchronizer.attachProductIds(snapshots);

            CrawlHistoryRepository.AppendResult appended =
                    historyRepository.append(batch.id(), snapshots);

            System.out.printf(
                    "이력 적재: 신규 %d건, 무변경 건너뜀 %d건%n",
                    appended.appendedProducts(),
                    appended.skippedUnchanged()
            );

            // ④ 라이브 반영
            CreditLoanRateSynchronizer.SyncResult syncResult =
                    synchronizer.synchronize(snapshots, batch.id());

            // ⑤ 마무리
            int successCount = (int) snapshots.stream()
                    .filter(ProductSnapshot::isStorable)
                    .count();

            // 새로 생긴 조건 / 사라진 조건은 사람이 봐야 하므로 배치 기록에 남긴다.
            List<String> warnings = new ArrayList<>(result.collectionWarnings());
            warnings.addAll(syncResult.conditionWarnings());

            boolean partial = successCount < result.targetProductCount()
                    || !warnings.isEmpty();

            batchRepository.finish(batch.id(), new CrawlBatchRepository.BatchFinish(
                    partial
                            ? CrawlBatchRepository.STATUS_PARTIAL
                            : CrawlBatchRepository.STATUS_SUCCESS,
                    result.targetProductCount(),
                    successCount,
                    true,
                    warnings
            ));

            printSummary(result, syncResult, warnings);

        } catch (Exception exception) {
            System.err.println("크롤링 실행 실패: " + exception.getMessage());
            exception.printStackTrace();

            if (batch != null) {
                markFailed(batchRepository, batch.id(), exception);
            }
            System.exit(1);
        }
    }

    private static LoanCollectionResult collect(
            boolean headless,
            CrawlUrlSettings urlSettings
    ) throws Exception {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(headless)
                            .setSlowMo(headless ? 0 : 100)
            );

            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setLocale("ko-KR")
                            .setUserAgent(
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                            + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                            + "Chrome/150.0.0.0 Safari/537.36"
                            )
            );

            try {
                return new KbLoanCollector(context, urlSettings).collect();
            } finally {
                context.close();
                browser.close();
            }
        }
    }

    private static void markFailed(
            CrawlBatchRepository batchRepository,
            long batchId,
            Exception cause
    ) {
        try {
            batchRepository.finish(batchId, new CrawlBatchRepository.BatchFinish(
                    CrawlBatchRepository.STATUS_FAILED,
                    0,
                    0,
                    false,
                    List.of(String.valueOf(cause))
            ));
        } catch (Exception recordFailure) {
            // 실패 기록마저 실패하면 배치는 RUNNING 으로 남아 좀비로 잡힌다.
            System.err.println(
                    "배치 실패 기록에도 실패했습니다: " + recordFailure.getMessage()
            );
        }
    }

    /** FinlifeGradeRates 는 "202606", credit_crawling_batch_history 는 CHAR(7) 'YYYY-MM' 을 쓴다. */
    private static String disclosureMonthForColumn(String disclosureMonth) {
        if (disclosureMonth == null || disclosureMonth.length() != 6) {
            return disclosureMonth;
        }
        return disclosureMonth.substring(0, 4) + "-" + disclosureMonth.substring(4);
    }

    /**
     * 이 배치를 누가 돌렸는지.
     *
     * 스케줄러가 도는 서버에서는 application.properties 에 kb.executedBy=scheduler 를
     * 박아두고, 사람이 수동으로 돌릴 때만 -Dkb.executedBy=... 로 덮어쓴다.
     * 아무것도 없으면 OS 계정 이름이 들어가는데(윈도우에서는 'pc' 같은 값),
     * 그것만으로는 정기 실행인지 사람이 돌린 건지 구분이 안 된다.
     */
    private static String executedBy() {
        return PropertySource.load().value(
                "kb.executedBy",
                "KB_EXECUTED_BY",
                System.getProperty("user.name", "unknown")
        );
    }

    private static void printSummary(
            LoanCollectionResult result,
            CreditLoanRateSynchronizer.SyncResult syncResult,
            List<String> warnings
    ) {
        System.out.printf(
                "DB 동기화 완료: 상품 생성 %d개, 상품 갱신 %d개, "
                        + "금리 저장 %d행, 조건 저장 %d행%n",
                syncResult.createdProducts(),
                syncResult.updatedProducts(),
                syncResult.savedRateRows(),
                syncResult.savedConditionRows()
        );

        System.out.println();
        System.out.println("수집 완료 (" + result.collectMode() + ")");
        System.out.println("대상 상품 수: " + result.targetProductCount());

        result.products().forEach(product -> System.out.printf(
                "- [%s] %s | 금리행 %d개 | 우대조건 %d개 | 검증=%s%n",
                product.product().productCode(),
                product.product().productName(),
                product.rateRows().size(),
                product.preferentialConditions().size(),
                product.validation().status()
        ));

        if (!warnings.isEmpty()) {
            System.out.println();
            System.out.println("경고 " + warnings.size() + "건:");
            warnings.forEach(warning -> System.out.println("  - " + warning));
        }
    }
}
