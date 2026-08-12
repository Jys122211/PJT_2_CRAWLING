package org.example.kbloan.persistence;

import org.example.kbloan.model.CrawledCondition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 크롤링한 우대조건·자격조건을 라이브 테이블에 반영한다.
 *
 * <p><b>같은 조건인지 어떻게 판정하나</b><br>
 * 대조용 컬럼을 따로 두지 않고, 조회할 때 열쇠를 만들어 비교한다.
 * <pre>
 *   우대조건 : 이름(공백제거·소문자) + "|" + 우대율   예) kb스타뱅킹이용우대|0.1
 *   자격조건 : 원문(공백제거·소문자)
 * </pre>
 * ⚠ 그래서 credit_loan_preferential_condition_question_name 은 KB 페이지 문구와
 * 글자까지 같아야 한다. 이 값을 고치면 크롤러가 기존 조건을 못 알아보고 중복을 만든다.
 * 화면에 보여줄 문구는 프론트엔드에서 따로 관리하므로 DB 문구를 고칠 일은 없다.
 *
 * <p><b>새 조건을 발견하면</b><br>
 * 질문을 등록하고 상품에 연결한다. 크롤러의 일은 사이트에 있는 것을
 * 가져와 저장하는 데까지다. 무엇이 새로 들어왔는지는 경고로 남긴다.
 */
public final class ConditionSynchronizer {

    public Result syncPreferential(
            Connection connection,
            long productId,
            String productName,
            List<CrawledCondition> crawled
    ) throws SQLException {

        if (crawled.isEmpty()) {
            return Result.empty();
        }

        Map<String, Long> byKey = preferentialQuestionIds(connection);

        List<String> newQuestions = new ArrayList<>();
        List<Long> linkedQuestionIds = new ArrayList<>();

        for (CrawledCondition condition : crawled) {
            Long questionId = byKey.get(condition.sourceKey());

            if (questionId == null) {
                questionId = insertPreferentialQuestion(connection, condition);
                byKey.put(condition.sourceKey(), questionId);
                newQuestions.add(
                        "새 우대조건 등록(질문 ID " + questionId + "): "
                                + condition.name()
                                + " " + condition.discountRate() + "%p"
                );
            }

            linkPreferential(connection, productId, questionId, condition);
            linkedQuestionIds.add(questionId);
        }

        return new Result(
                newQuestions,
                missingPreferential(connection, productId, productName, linkedQuestionIds),
                linkedQuestionIds.size()
        );
    }

    public Result syncQualification(
            Connection connection,
            long productId,
            String productName,
            List<CrawledCondition> crawled
    ) throws SQLException {

        if (crawled.isEmpty()) {
            return Result.empty();
        }

        Map<String, Long> byKey = qualificationQuestionIds(connection);

        List<String> newQuestions = new ArrayList<>();
        List<Long> linkedQuestionIds = new ArrayList<>();

        for (CrawledCondition condition : crawled) {
            Long questionId = byKey.get(condition.sourceKey());

            if (questionId == null) {
                questionId = insertQualificationQuestion(connection, condition);
                byKey.put(condition.sourceKey(), questionId);
                newQuestions.add(
                        "새 자격조건 등록(질문 ID " + questionId + "): "
                                + shorten(condition.name())
                );
            }

            linkQualification(connection, productId, questionId);
            linkedQuestionIds.add(questionId);
        }

        return new Result(
                newQuestions,
                missingQualification(connection, productId, productName, linkedQuestionIds),
                linkedQuestionIds.size()
        );
    }

    /**
     * 이름 + 우대율 조합으로 기존 질문을 찾는다.
     * 우대율은 credit_loan_preferential_rate 에 있으므로 조인해서 가져온다.
     */
    private Map<String, Long> preferentialQuestionIds(
            Connection connection
    ) throws SQLException {

        Map<String, Long> byKey = new HashMap<>();

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT q.credit_loan_preferential_condition_question_id, "
                        + "       q.credit_loan_preferential_condition_question_name, "
                        + "       r.discount_rate "
                        + "  FROM credit_loan_preferential_condition_question q "
                        + "  JOIN credit_loan_preferential_rate r "
                        + "    ON r.credit_loan_preferential_condition_question_id "
                        + "       = q.credit_loan_preferential_condition_question_id"
        ); ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                String key = CrawledCondition.keyOf(
                        resultSet.getString(2),
                        resultSet.getBigDecimal(3)
                );
                byKey.putIfAbsent(key, resultSet.getLong(1));
            }
        }

        return byKey;
    }

    private Map<String, Long> qualificationQuestionIds(
            Connection connection
    ) throws SQLException {

        Map<String, Long> byKey = new HashMap<>();

        // 자격조건은 화면 문구를 질문형으로 다시 쓰기 때문에 문구로는 절대 안 맞는다.
        // 그래서 KB 원문에서 뽑은 열쇠를 따로 들고 대조한다.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT qualification_condition_question_id, crawl_source_key "
                        + "FROM credit_loan_qualification_condition_question "
                        + "WHERE crawl_source_key IS NOT NULL"
        ); ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                byKey.putIfAbsent(resultSet.getString(2), resultSet.getLong(1));
            }
        }

        return byKey;
    }

    private long insertPreferentialQuestion(
            Connection connection,
            CrawledCondition condition
    ) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO credit_loan_preferential_condition_question ("
                        + "credit_loan_preferential_condition_question_name, "
                        + "credit_loan_preferential_condition_question_detail"
                        + ") VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setString(1, cut(condition.name(), 100));
            statement.setString(2, cut(orEmpty(condition.detail()), 300));
            statement.executeUpdate();

            return generatedKey(statement, condition.name());
        }
    }

    private long insertQualificationQuestion(
            Connection connection,
            CrawledCondition condition
    ) throws SQLException {

        // 열쇠를 같이 넣어야 다음 실행에서 이 질문을 다시 찾아낸다.
        // 안 넣으면 매번 같은 조건이 새로 쌓인다.
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO credit_loan_qualification_condition_question ("
                        + "question_text, crawl_source_key) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setString(1, cut(condition.name(), 300));
            statement.setString(2, cut(condition.sourceKey(), 300));
            statement.executeUpdate();

            return generatedKey(statement, condition.name());
        }
    }

    private void linkPreferential(
            Connection connection,
            long productId,
            long questionId,
            CrawledCondition condition
    ) throws SQLException {

        int updated;

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE credit_loan_preferential_rate SET discount_rate = ? "
                        + "WHERE loan_product_id = ? "
                        + "AND credit_loan_preferential_condition_question_id = ?"
        )) {
            statement.setBigDecimal(1, condition.discountRate());
            statement.setLong(2, productId);
            statement.setLong(3, questionId);
            updated = statement.executeUpdate();
        }

        if (updated > 0) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO credit_loan_preferential_rate ("
                        + "loan_product_id, credit_loan_preferential_condition_question_id, "
                        + "discount_rate) VALUES (?, ?, ?)"
        )) {
            statement.setLong(1, productId);
            statement.setLong(2, questionId);
            statement.setBigDecimal(3, condition.discountRate());
            statement.executeUpdate();
        }
    }

    /**
     * 상품에 자격조건을 건다. 이 테이블은 매핑만 담아 갱신할 값이 없으므로
     * 없을 때만 넣는다.
     */
    private void linkQualification(
            Connection connection,
            long productId,
            long questionId
    ) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO credit_loan_qualification_condition ("
                        + "loan_product_id, credit_loan_qualification_condition_question_id) "
                        + "SELECT ?, ? FROM DUAL WHERE NOT EXISTS ("
                        + "  SELECT 1 FROM credit_loan_qualification_condition "
                        + "   WHERE loan_product_id = ? "
                        + "     AND credit_loan_qualification_condition_question_id = ?)"
        )) {
            statement.setLong(1, productId);
            statement.setLong(2, questionId);
            statement.setLong(3, productId);
            statement.setLong(4, questionId);
            statement.executeUpdate();
        }
    }

    /** DB 에는 걸려 있는데 이번 페이지에 없던 자격조건. 지우지 않고 알리기만 한다. */
    private List<String> missingQualification(
            Connection connection,
            long productId,
            String productName,
            List<Long> linkedQuestionIds
    ) throws SQLException {

        List<String> missing = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT credit_loan_qualification_condition_question_id "
                        + "FROM credit_loan_qualification_condition "
                        + "WHERE loan_product_id = ?"
        )) {
            statement.setLong(1, productId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long questionId = resultSet.getLong(1);
                    if (!linkedQuestionIds.contains(questionId)) {
                        missing.add(
                                productName + "의 자격조건(질문 ID " + questionId
                                        + ")이 이번 페이지에 없습니다"
                                        + " — 삭제 여부는 직접 확인해주세요"
                        );
                    }
                }
            }
        }

        return missing;
    }

    /** DB 에는 연결돼 있는데 이번 페이지에 없던 우대조건. 지우지 않고 알리기만 한다. */
    private List<String> missingPreferential(
            Connection connection,
            long productId,
            String productName,
            List<Long> linkedQuestionIds
    ) throws SQLException {

        List<String> missing = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT credit_loan_preferential_condition_question_id "
                        + "FROM credit_loan_preferential_rate WHERE loan_product_id = ?"
        )) {
            statement.setLong(1, productId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long questionId = resultSet.getLong(1);
                    if (!linkedQuestionIds.contains(questionId)) {
                        missing.add(
                                productName + "의 우대조건(질문 ID " + questionId
                                        + ")이 이번 페이지에 없습니다"
                                        + " — 삭제 여부는 직접 확인해주세요"
                        );
                    }
                }
            }
        }

        return missing;
    }

    private static long generatedKey(
            PreparedStatement statement,
            String label
    ) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("질문 ID를 받지 못했습니다: " + label);
            }
            return keys.getLong(1);
        }
    }

    private static String cut(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String shorten(String value) {
        return value == null || value.length() <= 40
                ? value
                : value.substring(0, 40) + "…";
    }

    public record Result(
            List<String> newQuestions,
            List<String> missingConditions,
            int linkedCount
    ) {
        static Result empty() {
            return new Result(List.of(), List.of(), 0);
        }

        public List<String> warnings() {
            List<String> all = new ArrayList<>(newQuestions);
            all.addAll(missingConditions);
            return all;
        }
    }

}
