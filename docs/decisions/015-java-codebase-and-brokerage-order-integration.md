# ADR 015: Java 코드베이스와 증권 주문 외부 API 연동 구조

## 상태

채택

## 배경

기존 저장소는 Kotlin 기반 이미지 작업 오케스트레이션과 Mock Worker 연동을 중심으로 정리돼 있었다.
현재 요구사항은 Java 코드베이스를 유지하면서, 외부 증권사 API에 주문 제출과 상태 조회를 위임하는 구조로 전환하는 것이다.
핵심 가치는 언어 자체보다도 공개 API contract, idempotency, 비동기 처리, 재시도, stale recovery, 결과 보존, 운영 문서 정합성을 안정적으로 유지하는 데 있다.

## 결정

- main source 와 test source 는 Java 17 기준으로 유지한다.
- 공개 API 리소스는 `image job` 대신 `stock order job`으로 정의하고 base path 를 `/api/v1/stock-orders`로 둔다.
- 주문 작업 요청은 `brokerageCode`, `accountNumber`, `symbol`, `side`, `orderType`, `quantity`, `price`를 사용한다.
- 내부 작업 상태는 `QUEUED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`, `FAILED`를 유지한다.
- 외부 증권사 연동은 `BrokerageClient` 인터페이스와 `RestBrokerageClient` 구현으로 분리한다.
- `RestBrokerageClient`는 token issuance, cached token reuse, `401` refresh retry, timeout/4xx/5xx error mapping 을 담당한다.
- 기본 증권사 path 는 `/oauth2/token`, `/v1/orders`, `/v1/orders/{orderId}`로 두고, 모두 환경 변수로 override 가능하게 한다.
- read API 에서는 account number 를 마스킹해 노출한다.
- 비동기 처리 구조는 기존과 동일하게 DB-backed queue + scheduler + lease/recovery 모델을 유지한다.
- 처리 보장 모델은 `at-least-once`를 유지한다.
- terminal job 결과 보존 기간은 7일로 유지한다.
- 이전 이미지/Mock Worker/Kotlin 관련 ADR은 역사적 기록으로만 유지하고, 현재 활성 기준은 이 ADR로 단일화한다.

## 이유

- Java-only 코드는 현재 팀 운영 표준과 채용 과제 제출 요구를 가장 직접적으로 만족한다.
- 증권사 API는 인증, 주문, 상태조회가 분리돼 있어 HTTP 클라이언트 계층을 명시적으로 나누는 편이 변경 대응이 쉽다.
- 비동기 오케스트레이션, idempotency, retry/recovery 같은 운영 조건은 도메인이 바뀌어도 그대로 가치가 있다.
- 계좌번호는 저장이 필요하더라도 조회 응답에서 그대로 노출하지 않는 편이 안전하다.

## 영향

- 장점:
  - Java 코드베이스로 실행/리뷰 경로가 단순해진다.
  - 외부 증권사 API 교체나 추가 adapter 도입 시 `brokerage` 계층만 확장하면 된다.
  - 기존의 성숙한 비동기 처리, retry, recovery 구조를 도메인 변경 후에도 재사용할 수 있다.
- 단점:
  - 이전 이미지/Worker 관련 ADR과 문서는 활성 설계와 분리해서 이해해야 한다.
  - 실제 증권사별 세부 스펙 차이는 추가 adapter 나 request/response mapper 확장이 필요하다.

## 검증

- `./gradlew test`
- `./gradlew test jacocoTestReport`
- `docs/api/README.md`, `docs/runbook/DEBUG.md`, `README.md`, `.env.example`, `docker/compose.yaml` 정합성 확인
