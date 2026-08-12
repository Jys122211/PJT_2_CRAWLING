package org.example.kbloan.parser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * output/raw 에 저장된 실제 KB 상세 페이지 텍스트로 검증한다.
 * KB 가 페이지 구조를 바꾸면 이 테스트가 먼저 깨진다.
 */
class ProductHeaderParserTest {

    private final ProductHeaderParser parser = new ProductHeaderParser();

    private ProductHeaderParser.ParsedHeader parseFile(String code)
            throws Exception {
        String text = Files.readString(Path.of("output/raw/" + code + ".txt"));
        return parser.parse(text);
    }

    @Test
    void 상세페이지에서_상품명과_한도를_읽는다() throws Exception {
        ProductHeaderParser.ParsedHeader a = parseFile("LN20001397");
        assertEquals("KB스타 신용대출Ⅱ(신규)", a.productName());
        assertEquals("스타뱅킹", a.joinChannel());
        assertEquals("최고 3.5억원", a.limitText());

        ProductHeaderParser.ParsedHeader b = parseFile("LN20001391");
        assertEquals("KB 신용대출", b.productName());
        assertEquals("영업점", b.joinChannel());
        assertEquals("최고 3.5억원", b.limitText());
    }

    @Test
    void URL에서_상품코드를_뽑는다() {
        assertEquals("LN20001391", ProductHeaderParser.productCodeFromUrl(
                "https://obank.kbstar.com/quics?page=C103429"
                        + "&cc=b104363:b104516&isNew=N"
                        + "&prcode=LN20001391&QSL=F"));
    }
}
