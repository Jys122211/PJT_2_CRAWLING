package org.example.kbloan.util;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 설정값을 한 가지 순서로만 읽는다.
 *
 *   1. 시스템 프로퍼티 (-Dkb.xxx=...)      — 일회성 실행/디버깅
 *   2. 환경변수 (KB_XXX)                    — 배포 환경
 *   3. application.properties               — 평소 값
 *   4. 코드에 박아둔 기본값                  — 최후
 *
 * DatabaseSettings 와 CrawlUrlSettings 가 같은 규칙을 쓰도록 여기 모아둔다.
 */
public final class PropertySource {

    private static final String DEFAULT_CONFIG_PATH =
            "src/main/java/application.properties";

    private final Properties applicationProperties;

    private PropertySource(Properties applicationProperties) {
        this.applicationProperties = applicationProperties;
    }

    public static PropertySource load() {
        String configuredPath = System.getProperty(
                "kb.config.file",
                System.getenv().getOrDefault(
                        "KB_CONFIG_FILE",
                        DEFAULT_CONFIG_PATH
                )
        );

        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        Properties properties = new Properties();

        if (!Files.exists(path)) {
            return new PropertySource(properties);
        }

        // Properties.load(InputStream) 은 자바 스펙상 ISO-8859-1 로 읽는다.
        // 그대로 두면 한글 값(예: kb.bankName=KB국민은행)이 깨지므로
        // Reader 로 넘겨 UTF-8 을 강제한다.
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return new PropertySource(properties);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "설정 파일을 읽을 수 없습니다: " + path,
                    exception
            );
        }
    }

    /**
     * @param propertyName  시스템 프로퍼티 이름. application.properties 의 키로도 쓴다
     * @param environmentName 환경변수 이름. 없으면 건너뛴다
     */
    public String value(
            String propertyName,
            String environmentName,
            String defaultValue
    ) {
        return value(
                propertyName, environmentName, propertyName, defaultValue
        );
    }

    /**
     * application.properties 의 키가 시스템 프로퍼티 이름과 다를 때 쓴다.
     * (예: 시스템 프로퍼티는 kb.db.url, 파일 키는 jdbc.url)
     */
    public String value(
            String propertyName,
            String environmentName,
            String applicationPropertyName,
            String defaultValue
    ) {
        String property = System.getProperty(propertyName);
        if (hasText(property)) {
            return property.trim();
        }

        if (environmentName != null) {
            String environment = System.getenv(environmentName);
            if (hasText(environment)) {
                return environment.trim();
            }
        }

        String applicationValue =
                applicationProperties.getProperty(applicationPropertyName);
        return hasText(applicationValue)
                ? applicationValue.trim()
                : defaultValue;
    }

    /**
     * 빈 문자열을 "값이 없음"이 아니라 "일부러 비웠음"으로 다뤄야 하는 설정용.
     * kb.detailUrl 을 빈 값으로 주면 목록 탐색 모드로 전환되는데,
     * 일반 value() 로 읽으면 기본값이 되살아나 의도가 뒤집힌다.
     */
    public String valueAllowingBlank(
            String propertyName,
            String environmentName,
            String defaultValue
    ) {
        String property = System.getProperty(propertyName);
        if (property != null) {
            return property.trim();
        }

        if (environmentName != null) {
            String environment = System.getenv(environmentName);
            if (environment != null) {
                return environment.trim();
            }
        }

        String applicationValue =
                applicationProperties.getProperty(propertyName);
        return applicationValue != null
                ? applicationValue.trim()
                : defaultValue;
    }

    public boolean hasProperty(String applicationPropertyName) {
        return hasText(
                applicationProperties.getProperty(applicationPropertyName)
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
