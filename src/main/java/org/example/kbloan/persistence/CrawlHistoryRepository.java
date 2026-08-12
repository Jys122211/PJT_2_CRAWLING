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
import java.util.List;

/**
 * 이력 테이블 저장소 (append-only).
 *
 * 라이브 저장보다 먼저, 별도 트랜잭션으로 실행한다.
 * 라이브 반영이 검증에서 거부되거나 실패해도 "이날 이런 값이 있었다"는 기록은 남는다.
 */
public final class CrawlHistoryRepository {

    private final DatabaseSettings settings;

    public CrawlHistoryRepository(DatabaseSettings settings) {
        this.settings = settings;
    }

    public AppendResult append(
            long batchId,
            List<ProductSnapshot> snapshots
    ) throws SQLException {

        int appended = 0;
        int skipped = 0;

        try (Connection connection = DriverManager.getConnection(
                settings.url(), settings.username(), settings.password()
        )) {
            connection.setAutoCommit(false);

            try {
                for (ProductSnapshot snapshot : snapshots) {
                    String hash = SnapshotHasher.hash(snapshot);

                    if (hash.equals(latestHash(connection, snapshot))) {
                        skipped++;
                        continue;
                    }

                    long productsHistoryId =
                            insertProductHistory(connection, batchId, snapshot, hash);
                    insertRateHistory(connection, productsHistoryId, snapshot.rates());
                    appended++;
                }

                connection.commit();
            } catch (Exception exception) {
                rollbackQuietly(connection, exception);
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("크롤링 이력 저장 실패", exception);
            }
        }

        return new AppendResult(appended, skipped);
    }

    /**
     * 이 상품의 가장 최근 지문. 없으면 null.
     *
     * loan_product_id 가 있으면 idx_clph_product 를 그대로 탄다.
     * 라이브에 아직 없는 신규 상품만 product_name_key 로 찾는다.
     */
    private String latestHash(
            Connection connection,
            ProductSnapshot snapshot
    ) throws SQLException {

        String sql = snapshot.loanProductId() == null
                ? "SELECT snapshot_hash FROM credit_loan_products_history "
                        + "WHERE product_name_key = ? "
                        + "ORDER BY products_history_id DESC LIMIT 1"
                : "SELECT snapshot_hash FROM credit_loan_products_history "
                        + "WHERE loan_product_id = ? "
                        + "ORDER BY products_history_id DESC LIMIT 1";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (snapshot.loanProductId() == null) {
                statement.setString(1, snapshot.productNameKey());
            } else {
                statement.setLong(1, snapshot.loanProductId());
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private long insertProductHistory(
            Connection connection,
            long batchId,
            ProductSnapshot snapshot,
            String hash
    ) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO credit_loan_products_history ("
                        + "crawl_batch_id, loan_product_id, product_name, product_name_key, "
                        + "detail_url, loan_limit, loan_limit_raw, max_discount_rate, "
                        + "source_state, collect_status, failure_reason, snapshot_hash"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setLong(1, batchId);

            if (snapshot.loanProductId() == null) {
                statement.setNull(2, Types.BIGINT);
            } else {
                statement.setLong(2, snapshot.loanProductId());
            }

            statement.setString(3, snapshot.productName());
            statement.setString(4, snapshot.productNameKey());
            statement.setString(5, snapshot.detailUrl());

            if (snapshot.loanLimit() == null) {
                statement.setNull(6, Types.BIGINT);
            } else {
                statement.setLong(6, snapshot.loanLimit());
            }

            statement.setString(7, snapshot.loanLimitRaw());
            statement.setBigDecimal(8, snapshot.maxDiscountRate());
            statement.setString(9, snapshot.sourceState());
            statement.setString(10, snapshot.collectStatus());
            statement.setString(11, snapshot.failureReason());
            statement.setString(12, hash);

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException(
                            "상품 이력 ID를 받지 못했습니다: " + snapshot.productName()
                    );
                }
                return keys.getLong(1);
            }
        }
    }

    private void insertRateHistory(
            Connection connection,
            long productsHistoryId,
            List<ResolvedRate> rates
    ) throws SQLException {

        if (rates.isEmpty()) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO credit_loan_grade_rate_history ("
                        + "products_history_id, rate_period_months, credit_grade, "
                        + "base_rate, spread_rate, rate_origin, rate_type_raw"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?)"
        )) {
            for (ResolvedRate rate : rates) {
                statement.setLong(1, productsHistoryId);
                statement.setInt(2, rate.periodMonths());
                statement.setInt(3, rate.creditGrade());
                statement.setBigDecimal(4, rate.baseRate());
                statement.setBigDecimal(5, rate.spreadRate());
                statement.setString(6, rate.origin());
                statement.setString(7, rate.rateTypeRaw());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void rollbackQuietly(
            Connection connection,
            Exception cause
    ) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            // 롤백 실패가 원인 예외를 가리지 않도록 붙여만 둔다.
            cause.addSuppressed(rollbackFailure);
        }
    }

    public record AppendResult(int appendedProducts, int skippedUnchanged) {
    }
}
