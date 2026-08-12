package org.example.kbloan.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.kbloan.model.FinlifeGradeRates;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 금융감독원 금융상품 한눈에의 개인신용대출 금리를 조회한다. */
public final class FinlifeCreditLoanClient {
    private static final String DEFAULT_API_URL =
            "https://finlife.fss.or.kr/finlifeapi/creditLoanProductsSearch.json";
    private static final String TOP_FIN_GROUP_NO = "020000";
    private static final String KB_FINANCIAL_COMPANY_NO = "0010927";
    private static final String FINLIFE_PRODUCT_CODE = "KB200200000001";
    private static final String CREDIT_PRODUCT_TYPE = "1";
    // 202607처럼 crdt_grad_6이 누락되면 사용자가 제공한 202606의
    // 3등급(crdt_grad_5) 대비 4등급(crdt_grad_6) 비율로 보정한다.
    private static final BigDecimal JUNE_2026_BASE_GRADE_3 =
            new BigDecimal("2.89");
    private static final BigDecimal JUNE_2026_BASE_GRADE_4 =
            new BigDecimal("2.94");
    private static final BigDecimal JUNE_2026_SPREAD_GRADE_3 =
            new BigDecimal("3.16");
    private static final BigDecimal JUNE_2026_SPREAD_GRADE_4 =
            new BigDecimal("2.49");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FinlifeCreditLoanClient() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .build(),
                new ObjectMapper()
        );
    }

    FinlifeCreditLoanClient(
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public FinlifeGradeRates fetchLatestRates() {
        String apiKey = requiredApiKey();

        JsonNode firstPage = fetchPage(apiKey, 1);
        JsonNode firstResult = requiredResult(firstPage);
        int maxPageNo = Math.max(1, firstResult.path("max_page_no").asInt(1));

        ObjectNode combinedRoot = objectMapper.createObjectNode();
        ObjectNode combinedResult = combinedRoot.putObject("result");
        ArrayNode combinedOptions = combinedResult.putArray("optionList");
        appendOptions(firstResult, combinedOptions);

        for (int pageNo = 2; pageNo <= maxPageNo; pageNo++) {
            appendOptions(
                    requiredResult(fetchPage(apiKey, pageNo)),
                    combinedOptions
            );
        }

        return parseLatestRates(combinedRoot);
    }

    static FinlifeGradeRates parseLatestRates(JsonNode root) {
        JsonNode options = requiredResult(root).path("optionList");
        if (!options.isArray()) {
            throw new IllegalStateException(
                    "Finlife 응답에 optionList가 없습니다."
            );
        }

        String latestMonth = null;
        for (JsonNode option : options) {
            if (!isTarget(option)) {
                continue;
            }
            String month = option.path("dcls_month").asText("");
            if (!month.isBlank()
                    && (latestMonth == null || month.compareTo(latestMonth) > 0)) {
                latestMonth = month;
            }
        }

        if (latestMonth == null) {
            throw new IllegalStateException(
                    "Finlife에서 국민은행 일반신용대출 금리를 찾지 못했습니다: "
                            + FINLIFE_PRODUCT_CODE
            );
        }

        JsonNode baseRow = null;
        JsonNode spreadRow = null;
        for (JsonNode option : options) {
            if (!isTarget(option)
                    || !latestMonth.equals(option.path("dcls_month").asText())) {
                continue;
            }

            String rateType = option.path("crdt_lend_rate_type").asText();
            if ("B".equals(rateType)) {
                baseRow = option;
            } else if ("C".equals(rateType)) {
                spreadRow = option;
            }
        }

        if (baseRow == null || spreadRow == null) {
            throw new IllegalStateException(
                    "Finlife 최신 공시에서 기준금리(B) 또는 가산금리(C)가 없습니다: "
                            + latestMonth
            );
        }

        return new FinlifeGradeRates(
                latestMonth,
                gradeRates(baseRow, "기준금리"),
                gradeRates(spreadRow, "가산금리")
        );
    }

    private JsonNode fetchPage(String apiKey, int pageNo) {
        String apiUrl = System.getProperty(
                "kb.finlife.apiUrl",
                DEFAULT_API_URL
        );
        String query = "auth=" + encode(apiKey)
                + "&topFinGrpNo=" + encode(TOP_FIN_GROUP_NO)
                + "&pageNo=" + pageNo;

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(apiUrl + "?" + query)
                )
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Finlife API 호출 실패: HTTP " + response.statusCode()
                );
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Finlife API 호출 중 인터럽트가 발생했습니다.", exception
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Finlife API 응답을 읽지 못했습니다.", exception
            );
        }
    }

    private static JsonNode requiredResult(JsonNode root) {
        JsonNode result = root == null ? null : root.path("result");
        if (result == null || result.isMissingNode() || result.isNull()) {
            throw new IllegalStateException("Finlife 응답에 result가 없습니다.");
        }

        String errorCode = result.path("err_cd").asText("");
        if (!errorCode.isBlank() && !"000".equals(errorCode)) {
            throw new IllegalStateException(
                    "Finlife API 오류: " + errorCode + " / "
                            + result.path("err_msg").asText("알 수 없는 오류")
            );
        }
        return result;
    }

    private static void appendOptions(
            JsonNode result,
            ArrayNode target
    ) {
        JsonNode options = result.path("optionList");
        if (options.isArray()) {
            options.forEach(target::add);
        }
    }

    private static boolean isTarget(JsonNode option) {
        return KB_FINANCIAL_COMPANY_NO.equals(
                option.path("fin_co_no").asText()
        ) && FINLIFE_PRODUCT_CODE.equals(
                option.path("fin_prdt_cd").asText()
        ) && CREDIT_PRODUCT_TYPE.equals(
                option.path("crdt_prdt_type").asText()
        );
    }

    private static Map<Integer, BigDecimal> gradeRates(
            JsonNode row,
            String label
    ) {
        Map<Integer, BigDecimal> rates = new LinkedHashMap<>();
        rates.put(1, requiredDecimal(row, "crdt_grad_1", label));
        rates.put(2, requiredDecimal(row, "crdt_grad_4", label));
        BigDecimal gradeThree = requiredDecimal(
                row, "crdt_grad_5", label
        );
        rates.put(3, gradeThree);

        BigDecimal gradeFour = optionalDecimal(row, "crdt_grad_6");
        if (gradeFour == null) {
            gradeFour = estimateGradeFour(gradeThree, label);
            System.out.printf(
                    "Finlife %s의 crdt_grad_6 값이 없어 "
                            + "기존 3→4등급 비율로 %.4f를 계산했습니다.%n",
                    label,
                    gradeFour
            );
        }
        rates.put(4, gradeFour);
        return rates;
    }

    private static BigDecimal estimateGradeFour(
            BigDecimal currentGradeThree,
            String label
    ) {
        BigDecimal previousGradeThree;
        BigDecimal previousGradeFour;

        if ("기준금리".equals(label)) {
            previousGradeThree = JUNE_2026_BASE_GRADE_3;
            previousGradeFour = JUNE_2026_BASE_GRADE_4;
        } else if ("가산금리".equals(label)) {
            previousGradeThree = JUNE_2026_SPREAD_GRADE_3;
            previousGradeFour = JUNE_2026_SPREAD_GRADE_4;
        } else {
            throw new IllegalArgumentException(
                    "지원하지 않는 Finlife 금리 구분입니다: " + label
            );
        }

        return currentGradeThree
                .multiply(previousGradeFour)
                .divide(previousGradeThree, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal optionalDecimal(
            JsonNode row,
            String field
    ) {
        JsonNode value = row.path(field);
        if (value.isMissingNode()
                || value.isNull()
                || value.asText().isBlank()) {
            return null;
        }

        try {
            return value.decimalValue();
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static BigDecimal requiredDecimal(
            JsonNode row,
            String field,
            String label
    ) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "Finlife " + label + "의 " + field + " 값이 없습니다."
            );
        }
        try {
            return value.decimalValue();
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "Finlife " + label + "의 " + field
                            + " 값을 숫자로 변환할 수 없습니다.",
                    exception
            );
        }
    }

    private static String requiredApiKey() {
        String systemProperty = System.getProperty("kb.finlife.apiKey");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty.trim();
        }

        String environment = System.getenv("FINLIFE_API_KEY");
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }

        throw new IllegalStateException(
                "Finlife API 인증키가 없습니다. "
                        + "FINLIFE_API_KEY 환경변수를 설정해주세요."
        );
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
