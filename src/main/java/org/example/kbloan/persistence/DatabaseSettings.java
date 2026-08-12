package org.example.kbloan.persistence;

import org.example.kbloan.util.PropertySource;

public record DatabaseSettings(
        boolean enabled,
        String url,
        String username,
        String password
) {
    public static DatabaseSettings fromSystemProperties() {
        PropertySource source = PropertySource.load();

        String defaultEnabled = Boolean.toString(
                source.hasProperty("jdbc.url")
                        && source.hasProperty("jdbc.username")
                        && source.hasProperty("jdbc.password")
        );

        return new DatabaseSettings(
                Boolean.parseBoolean(source.value(
                        "kb.db.enabled",
                        "KB_DB_ENABLED",
                        "kb.db.enabled",
                        defaultEnabled
                )),
                normalizeJdbcUrl(source.value(
                        "kb.db.url",
                        "KB_DB_URL",
                        "jdbc.url",
                        "jdbc:mysql://localhost:3306/scoula_db"
                                + "?serverTimezone=Asia/Seoul"
                                + "&characterEncoding=UTF-8"
                )),
                source.value(
                        "kb.db.username",
                        "KB_DB_USERNAME",
                        "jdbc.username",
                        "scoula"
                ),
                source.value(
                        "kb.db.password",
                        "KB_DB_PASSWORD",
                        "jdbc.password",
                        ""
                )
        );
    }

    /**
     * 앱은 SQL 로깅용 log4jdbc 드라이버를 쓰지만, 크롤러는 순수 JDBC 로 붙는다.
     * application.properties 를 공유하므로 여기서 접두사를 걷어낸다.
     */
    private static String normalizeJdbcUrl(String url) {
        if (url.startsWith("jdbc:log4jdbc:mysql:")) {
            return "jdbc:mysql:" + url.substring(
                    "jdbc:log4jdbc:mysql:".length()
            );
        }
        return url;
    }
}
