package org.example.kbloan.persistence;

import org.example.kbloan.model.ProductSnapshot;
import org.example.kbloan.model.ResolvedRate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 스냅샷의 내용 지문(SHA-256)을 만든다.
 *
 * 직전에 기록한 지문과 같으면 값이 하나도 안 바뀌었다는 뜻이므로 적재를 건너뛴다.
 *
 * 위험한 쪽은 SHA-256 자체의 충돌이 아니라 "재료에서 빠뜨린 필드"다.
 * 해시에 넣지 않은 값은 아무리 바뀌어도 변경으로 잡히지 않으므로,
 * 이력 테이블에 저장하는 컬럼은 원문 컬럼까지 전부 넣는다.
 *
 * 해시가 어긋나도 라이브 반영에는 영향이 없다. CreditLoanRateSynchronizer 는
 * 지문을 보지 않고 항상 현재값을 갱신한다.
 */
public final class SnapshotHasher {

    /**
     * 필드 구분자. 상품명이나 금리에 들어갈 일이 없는 조합을 쓴다.
     * 구분자가 없으면 ("12", "3") 과 ("1", "23") 이 같은 지문이 되어버린다.
     */
    private static final String SEPARATOR = "|#|";

    private SnapshotHasher() {
    }

    public static String hash(ProductSnapshot snapshot) {
        List<String> fields = new ArrayList<>();

        // credit_loan_products_history 에 저장하는 값 전부
        fields.add(nullSafe(snapshot.productName()));
        fields.add(nullSafe(snapshot.productNameKey()));
        fields.add(nullSafe(snapshot.detailUrl()));
        fields.add(nullSafe(snapshot.loanLimit()));
        fields.add(nullSafe(snapshot.loanLimitRaw()));
        fields.add(normalize(snapshot.maxDiscountRate()));
        fields.add(nullSafe(snapshot.sourceState()));
        fields.add(nullSafe(snapshot.collectStatus()));
        fields.add(nullSafe(snapshot.failureReason()));

        // 금리행 순서가 흔들려도 같은 내용이면 같은 지문이 나오도록 정렬한다.
        List<String> rateLines = new ArrayList<>();
        for (ResolvedRate rate : snapshot.rates()) {
            rateLines.add(String.join(
                    ":",
                    String.valueOf(rate.periodMonths()),
                    String.valueOf(rate.creditGrade()),
                    normalize(rate.baseRate()),
                    normalize(rate.spreadRate()),
                    nullSafe(rate.origin()),
                    nullSafe(rate.rateTypeRaw())
            ));
        }
        rateLines.sort(null);
        fields.addAll(rateLines);

        return sha256(String.join(SEPARATOR, fields));
    }

    /**
     * 4.31 과 4.310 은 같은 값이다. 표기 차이 때문에 없던 변경이 생기지 않도록
     * 뒤따르는 0을 떼고 비교한다.
     */
    private static String normalize(BigDecimal value) {
        return value == null
                ? ""
                : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
        }
    }

    private static String nullSafe(Object value) {
        return Objects.toString(value, "");
    }
}
