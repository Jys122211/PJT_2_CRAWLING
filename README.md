# KB 신용대출 수집 샘플

기본 실행은 DB를 변경하지 않고 JSON과 원문 HTML 파일만 저장합니다.
`kb.db.enabled=true`로 실행하면 `scoula_db`의 신용대출 상품과
등급별 금리를 함께 동기화합니다.

1. KB 담보대출 상품 목록 수집
2. 신용대출 대상 상품 필터
3. 상세 페이지 수집
   - 대출신청자격
   - 금리표
   - 우대금리
4. 수집 결과 검증
5. `output/kb-credit-loan-products.json` 출력

## 환경

- JDK 17
- Gradle
- Playwright Java 1.60.0
- Jsoup 1.22.2

## 실행

Windows PowerShell 기준:

```powershell
gradlew.bat playwrightInstall
gradlew.bat run
```

Gradle Wrapper가 없는 경우 IntelliJ의 Gradle 도구 창에서
`playwrightInstall` 실행 후 `run`을 실행하거나, 로컬 Gradle로 실행합니다.

```powershell
gradle playwrightInstall
gradle run
```

브라우저 화면을 보면서 디버깅:

```powershell
gradle run -Dkb.headless=false
```

대상 상품 키워드 변경:

```powershell
gradle run -Dkb.targetKeywords=신용대출
```

결과:

```text
output/
├─ kb-credit-loan-products.json
└─ raw/
   ├─ LN20001397.html
   └─ LN20001397.txt
```

## 주의

KB 페이지의 HTML 구조가 변경되면 목록 카드 탐색 규칙이나 섹션 제목 별칭을
수정해야 합니다. 상세 URL을 목록에서 찾지 못하면 알려진 상품코드의
상세 URL 후보를 순서대로 시도합니다.

## 2026-07-16 수정사항

기존 버전에서 자격조건과 우대상세조건이 비어 있던 문제를 수정했습니다.

- DOM의 첫 번째 `우대금리` 표 헤더를 실제 우대조건 영역으로 잘못 선택하던 문제 수정
- KB 상세페이지가 `%p` 대신 `%`로 표시하는 우대금리도 파싱
- Playwright의 `body.innerText()`를 함께 저장하고 텍스트 기준으로 섹션 추출
- 우대조건 바로 다음 줄의 상세 설명을 `conditionDetail`에 연결
- 금리표 HTML 파싱 실패 시 본문 텍스트 표 파싱으로 보완
- `상품내용의 변경에 관한 사항` 이후 과거 조건은 현재 조건에서 제외
- 원문 확인용 `output/raw/{상품코드}.txt` 추가

## scoula_db 금리 동기화

기본적으로 `src/main/java/application.properties`의 `jdbc.url`,
`jdbc.username`, `jdbc.password`를 읽습니다. 백엔드용
`jdbc:log4jdbc:mysql:` URL은 크롤러에서 사용할 수 있도록
`jdbc:mysql:` URL로 자동 변환합니다. 다른 설정 파일을 사용하려면
`KB_CONFIG_FILE`에 파일 경로를 지정할 수 있습니다.

상품명에서 공백과 끝의 `(신규)`, `(신상품)`, `(판매중)`을 제거한 값을
비교합니다. 같은 상품이 있으면 해당 `loan_product_id`를 사용해 갱신하고,
없으면 `credit_loan_products`에 새 상품을 생성합니다.

금리는 KB 페이지의 3등급 기준값과 실행 시 Finlife API에서 직접 조회한
최신 등급비율로 계산합니다.

```text
등급별 기준금리 = 크롤링 기준금리 × Finlife 기준금리(등급) / Finlife 기준금리(3등급)
등급별 가산금리 = 크롤링 가산금리 × Finlife 가산금리(등급) / Finlife 가산금리(3등급)
```

Finlife 응답값은 다음과 같이 내부 1~4등급으로 바꿉니다.

```text
1등급=crdt_grad_1, 2등급=crdt_grad_4,
3등급=crdt_grad_5, 4등급=crdt_grad_6
```

Finlife API 인증키를 환경변수로 설정한 뒤 PowerShell에서 실행:

```powershell
$env:KB_DB_ENABLED='true'
$env:FINLIFE_API_KEY='발급받은_금융상품_한눈에_API_인증키'
gradle run
```

`application.properties`에 `jdbc.url`, `jdbc.username`, `jdbc.password`가
모두 있으면 DB 저장은 자동으로 켜집니다. JSON만 생성하려면
`KB_DB_ENABLED=false`로 실행합니다. Finlife 공시월과 기준·가산금리는
실행할 때마다 API에서 최신값을 선택하므로 직접 변경할 필요가 없습니다.
