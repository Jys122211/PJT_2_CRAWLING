package org.example.kbloan.persistence;

import org.example.kbloan.model.ProductSnapshot;
import org.example.kbloan.model.ResolvedRate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 라이브 테이블(credit_loan_products / credit_loan_grade_rate)에 현재값을 반영한다.
 *
 * 이력 저장이 끝난 뒤에 호출된다. 여기서 롤백이 나도 배치 기록과 이력은 이미
 * 커밋돼 있으므로, "돌렸지만 반영은 못 했다"가 DB에 남는다.
 */
public final class CreditLoanRateSynchronizer {

    private static final Pattern STATUS_SUFFIX = Pattern.compile(
            "[（(](?:신규|신상품|판매중)[）)]$"
    );

    private final DatabaseSettings settings;

    private final ConditionSynchronizer conditionSynchronizer =
            new ConditionSynchronizer();

    public CreditLoanRateSynchronizer(DatabaseSettings settings) {
        this.settings = settings;
    }

    /**
     * 라이브에 이미 있는 상품의 ID를 스냅샷에 채워 넣는다.
     * 이력을 저장하기 전에 호출해야 이력 행이 라이브 상품을 가리킬 수 있다.
     */
    public List<ProductSnapshot> attachProductIds(
            List<ProductSnapshot> snapshots
    ) throws SQLException {

        List<ProductSnapshot> attached = new ArrayList<>(snapshots.size());

        try (Connection connection = DriverManager.getConnection(
                settings.url(), settings.username(), settings.password()
        )) {
            Map<String, Long> idsByKey = productIdsByCanonicalName(connection);

            for (ProductSnapshot snapshot : snapshots) {
                Long existingId = idsByKey.get(snapshot.productNameKey());
                attached.add(existingId == null
                        ? snapshot
                        : new ProductSnapshot(
                                existingId,
                                snapshot.productName(),
                                snapshot.productNameKey(),
                                snapshot.detailUrl(),
                                snapshot.loanLimit(),
                                snapshot.loanLimitRaw(),
                                snapshot.maxDiscountRate(),
                                snapshot.sourceState(),
                                snapshot.collectStatus(),
                                snapshot.failureReason(),
                                snapshot.rates(),
                                snapshot.preferentialConditions(),
                                snapshot.qualificationConditions()
                        ));
            }
        }

        return attached;
    }

    public SyncResult synchronize(
            List<ProductSnapshot> snapshots,
            long batchId
    ) throws SQLException {

        if (!settings.enabled()) {
            return new SyncResult(0, 0, 0, 0, List.of());
        }

        int createdProducts = 0;
        int updatedProducts = 0;
        int savedRateRows = 0;
        int savedConditionRows = 0;
        List<String> conditionWarnings = new ArrayList<>();

        try (Connection connection = DriverManager.getConnection(
                settings.url(), settings.username(), settings.password()
        )) {
            connection.setAutoCommit(false);

            try {
                for (ProductSnapshot snapshot : snapshots) {
                    if (!snapshot.isStorable()) {
                        System.out.println(
                                "라이브 반영 건너뜀(" + snapshot.collectStatus()
                                        + "): " + snapshot.productName()
                        );
                        continue;
                    }

                    Long existingProductId = snapshot.loanProductId() != null
                            ? snapshot.loanProductId()
                            : findProductIdByName(connection, snapshot.productName());

                    long productId;
                    if (existingProductId == null) {
                        productId = insertProduct(connection, snapshot, batchId);
                        createdProducts++;
                        backfillHistoryProductId(
                                connection, productId, snapshot.productNameKey()
                        );
                    } else {
                        productId = existingProductId;
                        updateProduct(connection, productId, snapshot, batchId);
                        updatedProducts++;
                    }

                    for (ResolvedRate rate : snapshot.rates()) {
                        upsertGradeRate(connection, productId, rate, batchId);
                        savedRateRows++;
                    }

                    // 우대·자격조건도 같은 트랜잭션에서 반영한다.
                    // 금리만 갱신되고 조건은 옛날 것이 남는 어긋난 상태를 막는다.
                    ConditionSynchronizer.Result preferential =
                            conditionSynchronizer.syncPreferential(
                                    connection, productId,
                                    snapshot.productName(),
                                    snapshot.preferentialConditions()
                            );
                    ConditionSynchronizer.Result qualification =
                            conditionSynchronizer.syncQualification(
                                    connection, productId,
                                    snapshot.productName(),
                                    snapshot.qualificationConditions()
                            );

                    conditionWarnings.addAll(preferential.warnings());
                    conditionWarnings.addAll(qualification.warnings());
                    savedConditionRows += preferential.linkedCount()
                            + qualification.linkedCount();
                }

                connection.commit();
            } catch (Exception exception) {
                rollbackQuietly(connection, exception);
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("신용대출 금리 DB 동기화 실패", exception);
            }
        }

        return new SyncResult(
                createdProducts, updatedProducts, savedRateRows,
                savedConditionRows, List.copyOf(conditionWarnings)
        );
    }

    public static String canonicalProductName(String productName) {
        if (productName == null) {
            return "";
        }

        String normalized = productName
                .replaceAll("\\s+", "")
                .trim();

        return STATUS_SUFFIX.matcher(normalized)
                .replaceFirst("")
                .toLowerCase(Locale.ROOT);
    }

    private Map<String, Long> productIdsByCanonicalName(
            Connection connection
    ) throws SQLException {

        Map<String, Long> idsByKey = new LinkedHashMap<>();

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT loan_product_id, product_name FROM credit_loan_products"
        ); ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                String key = canonicalProductName(
                        resultSet.getString("product_name")
                );
                Long previous = idsByKey.putIfAbsent(
                        key, resultSet.getLong("loan_product_id")
                );
                if (previous != null) {
                    throw new SQLException(
                            "정규화한 상품명이 중복됩니다: "
                                    + resultSet.getString("product_name")
                    );
                }
            }
        }

        return idsByKey;
    }

    private Long findProductIdByName(
            Connection connection,
            String crawledProductName
    ) throws SQLException {
        return productIdsByCanonicalName(connection)
                .get(canonicalProductName(crawledProductName));
    }

    private long insertProduct(
            Connection connection,
            ProductSnapshot snapshot,
            long batchId
    ) throws SQLException {

        if (snapshot.loanLimit() == null) {
            throw new SQLException(
                    "신규 상품의 대출한도를 해석할 수 없습니다: "
                            + snapshot.productName()
                            + " / " + snapshot.loanLimitRaw()
            );
        }

        // 신규 상품은 방금 사이트에서 확인한 것이므로 is_deleted='N'.
        // 컬럼 DEFAULT 도 'N' 이지만, 의도를 드러내려고 명시한다.
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO credit_loan_products ("
                        + "product_name, loan_limit, max_discount_rate, "
                        + "is_deleted, last_batch_id, updated_at"
                        + ") VALUES (?, ?, ?, 'N', ?, CURRENT_TIMESTAMP)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setString(1, snapshot.productName());
            statement.setLong(2, snapshot.loanLimit());
            statement.setBigDecimal(
                    3,
                    snapshot.maxDiscountRate() == null
                            ? java.math.BigDecimal.ZERO
                            : snapshot.maxDiscountRate()
            );
            statement.setLong(4, batchId);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("신규 상품 ID를 받지 못했습니다.");
                }
                return keys.getLong(1);
            }
        }
    }

    /**
     * 신규 상품의 이력 행에 방금 만들어진 상품 ID를 채운다.
     *
     * 이력은 라이브보다 먼저 저장되므로, 처음 보는 상품은 그 시점에 ID가 없어
     * loan_product_id 가 NULL 로 들어간다. 그대로 두면 같은 상품인데 첫 줄만
     * ID 가 비어, loan_product_id 로 이력을 조회할 때 첫 수집 기록이 빠진다.
     *
     * 이력 테이블에 UPDATE 를 거는 유일한 곳이다. append-only 원칙의 예외이지만,
     * 이 컬럼은 "그날의 사실"이 아니라 라이브 행을 가리키는 연결 번호이고,
     * 당시에는 알 수 없던 값을 뒤늦게 채우는 것뿐이다.
     * 라이브 반영과 같은 트랜잭션에서 돌기 때문에, 상품 INSERT 가 롤백되면
     * 이 연결도 함께 사라진다.
     */
    private void backfillHistoryProductId(
            Connection connection,
            long productId,
            String productNameKey
    ) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE credit_loan_products_history "
                        + "SET loan_product_id = ? "
                        + "WHERE product_name_key = ? AND loan_product_id IS NULL"
        )) {
            statement.setLong(1, productId);
            statement.setString(2, productNameKey);
            statement.executeUpdate();
        }
    }

    private void updateProduct(
            Connection connection,
            long productId,
            ProductSnapshot snapshot,
            long batchId
    ) throws SQLException {

        // 이번 수집에 잡혔다는 것은 사이트에 아직 있다는 뜻이므로 is_deleted='N' 으로 되돌린다.
        // 반대로 사이트에서 사라진 상품은 애초에 여기까지 오지 않으므로 값이 그대로 남고,
        // 판매종료('Y') 판정은 사람이 한다. 크롤러는 소실 여부를 추측하지 않는다.
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE credit_loan_products SET "
                        + "loan_limit = COALESCE(?, loan_limit), "
                        + "max_discount_rate = COALESCE(?, max_discount_rate), "
                        + "is_deleted = 'N', "
                        + "last_batch_id = ?, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE loan_product_id = ?"
        )) {
            if (snapshot.loanLimit() == null) {
                statement.setNull(1, Types.BIGINT);
            } else {
                statement.setLong(1, snapshot.loanLimit());
            }

            if (snapshot.maxDiscountRate() == null) {
                statement.setNull(2, Types.DECIMAL);
            } else {
                statement.setBigDecimal(2, snapshot.maxDiscountRate());
            }

            statement.setLong(3, batchId);
            statement.setLong(4, productId);
            statement.executeUpdate();
        }
    }

    private void upsertGradeRate(
            Connection connection,
            long productId,
            ResolvedRate rate,
            long batchId
    ) throws SQLException {

        int updated;

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE credit_loan_grade_rate SET "
                        + "base_rate = ?, spread_rate = ?, last_batch_id = ? "
                        + "WHERE loan_product_id = ? "
                        + "AND rate_period_months = ? "
                        + "AND credit_grade = ?"
        )) {
            statement.setBigDecimal(1, rate.baseRate());
            statement.setBigDecimal(2, rate.spreadRate());
            statement.setLong(3, batchId);
            statement.setLong(4, productId);
            statement.setInt(5, rate.periodMonths());
            statement.setInt(6, rate.creditGrade());
            updated = statement.executeUpdate();
        }

        if (updated > 1) {
            throw new SQLException(
                    "등급별 금리 중복 행이 있습니다: productId="
                            + productId + ", period=" + rate.periodMonths()
                            + ", grade=" + rate.creditGrade()
            );
        }

        if (updated == 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO credit_loan_grade_rate ("
                            + "loan_product_id, rate_period_months, credit_grade, "
                            + "base_rate, spread_rate, last_batch_id"
                            + ") VALUES (?, ?, ?, ?, ?, ?)"
            )) {
                statement.setLong(1, productId);
                statement.setInt(2, rate.periodMonths());
                statement.setInt(3, rate.creditGrade());
                statement.setBigDecimal(4, rate.baseRate());
                statement.setBigDecimal(5, rate.spreadRate());
                statement.setLong(6, batchId);
                statement.executeUpdate();
            }
        }
    }

    private static void rollbackQuietly(
            Connection connection,
            Exception cause
    ) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    public record SyncResult(
            int createdProducts,
            int updatedProducts,
            int savedRateRows,
            int savedConditionRows,
            /** 새로 생긴 조건, 이번 페이지에 없던 조건 — 사람이 봐야 하는 것들 */
            List<String> conditionWarnings
    ) {
    }
}
