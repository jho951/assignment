# Java 재정렬 및 증권사 API 전환 체크리스트

## 목표

- Kotlin/image worker 중심 구현을 Java/stock order/brokerage 구조로 전환할 때 확인할 항목을 정리한다.

## 체크리스트

- [x] main source 를 `src/main/java` 기준으로 정리한다.
- [x] test source 를 `src/test/java` 기준으로 정리한다.
- [x] `build.gradle`, `gradle.properties`에서 Kotlin 전용 구성을 제거한다.
- [x] 공개 API를 `/api/v1/stock-orders`로 교체한다.
- [x] 요청 DTO를 주문 중심 필드로 교체한다.
- [x] `WorkerClient`를 `BrokerageClient`로 바꾸고 token/order/status 계층을 분리한다.
- [x] `401` refresh retry, timeout, `4xx/5xx` 매핑을 새 클라이언트에 반영한다.
- [x] query 응답에서 계좌번호 마스킹을 반영한다.
- [x] README, REQUIREMENTS, API 문서, 런북, ADR, prompts 를 함께 갱신한다.
- [x] `./gradlew test`와 `./gradlew test jacocoTestReport`를 통과시킨다.
