package org.example.kbloan.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * credit_crawling_batch_history 저장소.
 *
 * 다른 저장 작업과 트랜잭션을 절대 공유하지 않는다.
 * 이력·라이브 저장이 롤백돼도 "돌렸다 / 실패했다"는 기록은 남아야 하기 때문이다.
 * 그래서 start() / finish() 는 각자 커넥션을 열고 바로 커밋한다.
 */
public final class CrawlBatchRepository {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final int WARNINGS_MAX_LENGTH = 60_000;

    private final DatabaseSettings settings;

    public CrawlBatchRepository(DatabaseSettings settings) {
        this.settings = settings;
    }

    /**
     * 크롤링을 시작하기 전에 호출한다.
     * 여기서 바로 커밋하므로, 이후 프로세스가 죽어도 RUNNING 상태로 흔적이 남는다.
     */
    public Batch start(BatchStart start) throws SQLException {
        String batchUuid = UUID.randomUUID().toString();

        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO credit_crawling_batch_history ("
                             + "batch_uuid, bank_name, product_category, "
                             + "status, started_at, executed_by, "
                             + "finlife_disclosure_month"
                             + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS
             )) {

            statement.setString(1, batchUuid);
            statement.setString(2, start.bankName());
            statement.setString(3, start.productCategory());
            statement.setString(4, STATUS_RUNNING);
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            statement.setString(6, start.executedBy());
            setNullableString(statement, 7, start.finlifeDisclosureMonth());

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("배치 ID를 받지 못했습니다.");
                }
                return new Batch(keys.getLong(1), batchUuid);
            }
        }
    }

    /**
     * 성공이든 실패든 반드시 호출한다. 호출되지 않으면 RUNNING 으로 남아
     * "죽은 실행"으로 잡힌다.
     */
    public void finish(long batchId, BatchFinish finish) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE credit_crawling_batch_history SET "
                             + "status = ?, finished_at = ?, "
                             + "target_count = ?, success_count = ?, failed_count = ?, "
                             + "applied_to_live_yn = ?, warnings = ? "
                             + "WHERE crawl_batch_id = ?"
             )) {

            statement.setString(1, finish.status());
            statement.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            statement.setInt(3, finish.targetCount());
            statement.setInt(4, finish.successCount());
            statement.setInt(5, finish.targetCount() - finish.successCount());
            statement.setString(6, finish.appliedToLive() ? "Y" : "N");
            setNullableString(statement, 7, joinWarnings(finish.warnings()));
            statement.setLong(8, batchId);

            statement.executeUpdate();
        }
    }

    private Connection open() throws SQLException {
        // autoCommit 기본값(true)을 그대로 쓴다. 배치 기록은 되돌리지 않는다.
        return DriverManager.getConnection(
                settings.url(), settings.username(), settings.password()
        );
    }

    private static void setNullableString(
            PreparedStatement statement,
            int index,
            String value
    ) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String joinWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        String joined = String.join(System.lineSeparator(), warnings);
        return joined.length() <= WARNINGS_MAX_LENGTH
                ? joined
                : joined.substring(0, WARNINGS_MAX_LENGTH);
    }

    public record Batch(long id, String uuid) {
    }

    public record BatchStart(
            String bankName,
            String productCategory,
            String executedBy,
            String finlifeDisclosureMonth
    ) {
    }

    public record BatchFinish(
            String status,
            int targetCount,
            int successCount,
            boolean appliedToLive,
            List<String> warnings
    ) {
    }
}
