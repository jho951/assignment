

## 프로젝트 개요

이 저장소는 `Java 17 + Spring Boot` 기반의 `비동기 증권 주문 오케스트레이션 서버`입니다.
클라이언트는 주문 요청을 서버에 제출하고, 서버는 작업을 `QUEUED` 상태로 저장한 뒤 즉시 `jobId`를 반환합니다.
실제 주문 접수와 체결 상태 확인은 외부 증권사 API에 위임하며, 애플리케이션은 scheduler와 DB-backed queue를 사용해 주문 수명주기를 관리합니다.

```text
Client
  -> POST /api/v1/stock-orders
  -> Server stores job as QUEUED
  -> Scheduler submits order to brokerage API
  -> Scheduler polls brokerage order status
  -> Client polls status/result/list APIs with jobId
```

## 핵심 기능

- `POST /api/v1/stock-orders`로 주문 작업을 접수합니다.
- `Idempotency-Key` 기반으로 중복 요청을 제어합니다.
- 주문 상태, 결과, 목록 조회 API를 제공합니다.
- 외부 증권사 API 토큰 발급, 주문 제출, 주문 상태 조회를 별도 클라이언트 계층으로 분리했습니다.
- 일시 장애, timeout, `401` 재인증, stale `PROCESSING` 복구, 만료 cleanup 정책을 포함합니다.
- account number는 조회 응답에서 마스킹해 노출합니다.

## 빠른 시작

### 테스트

```bash
./gradlew test
```

커버리지 리포트까지 생성하려면:

```bash
./gradlew test jacocoTestReport
```

리포트 경로:

- `build/reports/jacoco/test/html/index.html`
- `build/reports/jacoco/test/jacocoTestReport.xml`

### 로컬 실행

```bash
./gradlew bootRun
```

- 기본 포트: `8080`
- 기본 로컬 DB: `jdbc:h2:file:./data/assignment`
- 기본 증권사 base URL: `https://brokerage.example`

주의:

- 기본 증권사 설정은 `placeholder`입니다.
- 애플리케이션은 기본 설정만으로도 기동되지만, 실제 주문 처리까지 검증하려면 `BROKERAGE_*` 환경 변수를 실사용 sandbox 또는 사내 mock/stub 서버에 맞게 바꿔야 합니다.

### 컨테이너 실행

스크립트 권한이 없으면 먼저 부여합니다.

```bash
chmod +x scripts/*.sh
```

시작:

```bash
./scripts/run.sh
```

중지:

```bash
./scripts/stop.sh
```

볼륨까지 제거:

```bash
./scripts/stop.sh -v
```

직접 compose를 쓰려면:

```bash
docker compose -f docker/compose.yaml up --build -d
```

## 환경 변수

### 데이터베이스

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

### 증권사 API

- `BROKERAGE_BASE_URL`
- `BROKERAGE_TOKEN_PATH`
- `BROKERAGE_ORDER_PATH`
- `BROKERAGE_ORDER_STATUS_PATH`
- `BROKERAGE_TIMEOUT_MS`
- `BROKERAGE_APP_KEY`
- `BROKERAGE_APP_SECRET`
- `BROKERAGE_CLIENT_ID`

기본 예시는 [.env.example](/Users/jhons/Downloads/assignment/.env.example)에 있습니다.

## API 개요

현재 공개 API는 REST polling 방식입니다.

| 목적 | Method | Path |
|---|---|---|
| 주문 작업 생성 | `POST` | `/api/v1/stock-orders` |
| 주문 작업 상태 조회 | `GET` | `/api/v1/stock-orders/{jobId}` |
| 주문 작업 결과 조회 | `GET` | `/api/v1/stock-orders/{jobId}/result` |
| 주문 작업 목록 조회 | `GET` | `/api/v1/stock-orders` |

### 주문 작업 생성

```http
POST /api/v1/stock-orders
Content-Type: application/json
Idempotency-Key: order-001
```

```json
{
  "brokerageCode": "KIS",
  "accountNumber": "12345678-01",
  "symbol": "005930",
  "side": "BUY",
  "orderType": "LIMIT",
  "quantity": 10,
  "price": 70000
}
```

응답 예시:

```json
{
  "jobId": "order_abc123",
  "status": "QUEUED",
  "createdAt": "2026-05-07T06:00:00Z"
}
```

### 상태 모델

```text
QUEUED
PROCESSING
RETRY_SCHEDULED
SUCCEEDED
FAILED
```

- `SUCCEEDED`, `FAILED`는 terminal state입니다.
- `attemptCount`는 scheduler가 job을 claim해 증권사 API 제출 또는 기존 주문 상태 poll 재개를 시도한 횟수입니다.

### 결과 조회

`SUCCEEDED`면 증권사 주문 ID, 체결 수량, 평균 체결가를 확인할 수 있습니다.
`FAILED`면 오류 코드와 메시지를 확인할 수 있습니다.

### 목록 조회

```http
GET /api/v1/stock-orders?page=0&size=20&status=SUCCEEDED
```

- 정렬: `createdAt DESC`, `jobId DESC`
- 필터: `status`
- `expiresAt`이 지난 terminal job은 cleanup 이전이라도 목록에서 제외됩니다.

## 설계 요약

### 비동기 오케스트레이션

- 주문 접수 API는 외부 증권사 응답까지 동기적으로 블로킹하지 않습니다.
- DB row가 queue 역할을 하며 scheduler가 due job을 claim해서 처리합니다.
- executor thread는 한 번 claim한 job에 대해 `submitOrder` 또는 `getOrderStatus` 한 번만 수행합니다.

### 중복 요청 처리

- `Idempotency-Key`는 필수입니다.
- 같은 key와 같은 body면 기존 job을 replay합니다.
- 같은 key와 다른 body면 `409 Conflict`를 반환합니다.
- replay 응답의 `status`는 기존 job의 현재 상태를 그대로 반영합니다.

### 외부 증권사 API 연동

- 외부 연동은 [RestBrokerageClient](/Users/jhons/Downloads/assignment/src/main/java/io/github/jho951/assignment/brokerage/RestBrokerageClient.java)로 분리했습니다.
- 토큰 발급은 lazy issuance 방식입니다.
- `401` 응답 시 cached token을 버리고 1회 재발급 후 즉시 재시도합니다.
- `429`, `5xx`, timeout은 retryable failure로 분류합니다.
- 기본 연동 경로는 `/oauth2/token`, `/v1/orders`, `/v1/orders/{orderId}`이며 모두 환경 변수로 override할 수 있습니다.

### 처리 보장과 복구

- 처리 보장 모델은 `at-least-once`입니다.
- stale `PROCESSING` 작업은 `leaseUntil` 기준으로 `RETRY_SCHEDULED` 또는 `FAILED`로 회복합니다.
- remote status가 `PENDING` 또는 `PARTIALLY_FILLED`면 local status는 `PROCESSING`을 유지한 채 lease를 풀고 다음 poll 시점으로 되돌립니다.

### 결과 보존

- terminal job 결과는 완료 시각 기준 7일 보존합니다.
- `expiresAt`이 지난 terminal job은 조회 API에서 즉시 숨깁니다.

## 문서

- 요구사항: [docs/REQUIREMENTS.md](/Users/jhons/Downloads/assignment/docs/REQUIREMENTS.md)
- 공개 API: [docs/api/README.md](/Users/jhons/Downloads/assignment/docs/api/README.md)
- ADR 인덱스: [docs/decisions/README.md](/Users/jhons/Downloads/assignment/docs/decisions/README.md)
- 디버그 런북: [docs/runbook/DEBUG.md](/Users/jhons/Downloads/assignment/docs/runbook/DEBUG.md)
- 프롬프트 카탈로그: [prompts/README.md](/Users/jhons/Downloads/assignment/prompts/README.md)
