# 구현 계획

## 목표

- 증권 주문 작업 접수, 상태 조회, 결과 조회, 목록 조회를 지원하는 Java 17 Spring Boot 백엔드를 구현한다.
- 외부 증권사 API 토큰 발급, 주문 제출, 주문 상태 조회를 별도 클라이언트 계층으로 분리한다.
- 상태 전이, 중복 요청 처리, 재시도, stale recovery, cleanup 정책을 코드와 테스트에 반영한다.

## 제약

- 공개 API는 `POST /api/v1/stock-orders`와 read API 세트를 사용한다.
- `Idempotency-Key`는 trim 후 `1..128`자, 영문 대소문자/숫자/`.`/`_`/`-`만 허용한다.
- 같은 key+같은 body는 replay, 같은 key+다른 body는 `409`, 같은 body+다른 key는 새 job으로 처리한다.
- 증권사 access token은 애플리케이션 startup 시 미리 발급하지 않고 최초 처리 시점에 lazy issuance 해야 한다.
- 컨테이너 예제와 `.env.example`는 `BROKERAGE_*` override를 노출해야 한다.
- 증권사 호출은 timeout 5초, 최대 3회 시도, `2초 -> 10초 -> 30초` backoff, `401` refresh retry 정책을 따른다.
- executor thread는 한 번 claim한 job에 대해 remote call 한 번만 수행한다.
- 작업 상태는 `QUEUED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`, `FAILED`만 사용한다.
- 처리 보장 모델은 `at-least-once`를 유지한다.
- terminal job 결과는 7일 보존한다.

## 단계

1. Java 코드베이스 정리와 기본 실행 검증
2. API 계약과 DTO 정의
3. `StockOrderJob`, 상태 전이, repository 정의
4. command/query service와 idempotency 구현
5. `BrokerageClient`, `RestBrokerageClient` 구현
6. processor/scheduler/recovery/cleanup 구현
7. 단위 테스트와 통합 테스트 작성
8. README, API 문서, 런북, ADR 정합성 마감

## 리스크와 대응

- 리스크: 증권사 API 장애 시 작업이 무한 `PROCESSING`에 머무를 수 있다.
- 대응: lease timeout, retry backoff, stale recovery 규칙을 강제한다.
- 리스크: 주문 제출과 상태 poll 사이에 프로세스가 중단되면 외부 상태와 내부 상태가 어긋날 수 있다.
- 대응: `at-least-once` 모델과 `externalOrderId` 기반 poll 재개 semantics 를 유지한다.
- 리스크: 계좌번호 노출이 read API에 그대로 남을 수 있다.
- 대응: query service에서 마스킹을 강제하고 테스트로 고정한다.
