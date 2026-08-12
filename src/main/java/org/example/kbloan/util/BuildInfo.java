package org.example.kbloan.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 빌드 시점에 박아둔 크롤러 버전(git sha)을 읽는다.
 * credit_crawling_batch_history.crawler_version 에 들어가, 값이 이상해졌을 때
 * "우리 코드가 바뀐 건지 KB가 바뀐 건지"를 가르는 근거가 된다.
 */
public final class BuildInfo {

    private static final String RESOURCE = "/build-info.properties";

    /** git 저장소가 아니거나 sha 를 못 읽었을 때 쓰는 값. */
    private static final String UNKNOWN = "unknown";

    private static final String VERSION = load();

    private BuildInfo() {
    }

    /**
     * 알아낼 수 없으면 "unknown".
     *
     * NULL 을 넣지 않는 이유: crawler_version 이 비어 있으면 "버전을 못 읽었다"인지
     * "그 컬럼을 안 쓰던 시절이다"인지 구분이 안 된다. 값이 하나로 통일돼 있어야
     * 나중에 sha 가 들어오기 시작한 시점을 찾을 수 있다.
     */
    public static String version() {
        return VERSION;
    }

    private static String load() {
        String override = System.getProperty("kb.version");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }

        try (InputStream input = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return UNKNOWN;
            }

            Properties properties = new Properties();
            properties.load(input);
            String sha = properties.getProperty("git.sha");

            // 리소스 치환이 안 된 경우("${gitSha}")도 알 수 없는 것으로 본다.
            if (sha == null || sha.isBlank() || sha.startsWith("${")) {
                return UNKNOWN;
            }
            return sha.trim();
        } catch (IOException exception) {
            return UNKNOWN;
        }
    }
}
