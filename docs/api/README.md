# 공개 API 가이드

이 문서는 클라이언트가 사용하는 공개 API 계약을 정의합니다.
외부 증권사 API 연동은 내부 구현 세부로 숨기고, 클라이언트는 `stock order job` 리소스를 기준으로 비동기 상태를 조회합니다.

## 설계 원칙

- 공개 API는 주문 작업 리소스 중심으로 설계한다.
- 작업 생성과 외부 증권사 체결 완료를 분리한다.
- 중복 요청 제어는 `Idempotency-Key` 헤더로 수행한다.
- 상태 조회와 결과 조회를 분리해 비완료 작업의 응답 의미를 명확히 한다.
- 계좌번호는 read API에서 마스킹해 노출한다.

## 리소스 모델

작업 리소스는 `stock order job`으로 정의한다.

- `jobId`: 서버가 발급한 작업 식별자
- `status`: `QUEUED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`, `FAILED`
- `brokerageCode`: 대상 증권사 코드
- `accountNumberMasked`: 마스킹된 계좌번호
- `symbol`: 종목코드
- `side`: `BUY` 또는 `SELL`
- `orderType`: `LIMIT` 또는 `MARKET`
- `quantity`: 주문 수량
- `price`: 지정가 금액, 시장가 주문이면 `null` 허용
- `attemptCount`: scheduler가 job을 claim해 주문 제출 또는 기존 증권사 주문 poll 재개를 시도한 횟수
- `brokerageOrderId`: 외부 증권사 주문 식별자
- `executionStatus`: 증권사 정규화 상태 (`PENDING`, `PARTIALLY_FILLED`, `FILLED`, `REJECTED`, `CANCELLED`)
- `filledQuantity`: 체결 수량
- `remainingQuantity`: 잔량
- `averageExecutedPrice`: 평균 체결가
- `createdAt`, `updatedAt`, `completedAt`, `expiresAt`
- `error`: 실패 시 노출하는 오류 정보

## 공통 규칙

- Base path: `/api/v1/stock-orders`
- Content-Type: `application/json`
- Accept: `application/json`
- 공개 API OpenAPI JSON endpoint: `/v3/api-docs`
- 공개 API Swagger UI endpoint: `/swagger-ui.html`
- 시간 필드는 RFC 3339 UTC 문자열을 사용한다.
- `expiresAt`이 지난 terminal job은 cleanup 이전이라도 상태/결과 조회에서 `404`, 목록 조회에서 제외한다.
- executor thread는 한 번 claim한 job에 대해 `submitOrder` 또는 `getOrderStatus` 한 번만 수행한다.
- remote status가 계속 `PENDING` 또는 `PARTIALLY_FILLED`면 job status는 `PROCESSING`을 유지한 채 lease를 해제하고 다음 poll 시점으로 되돌린다.

## 작업 생성

`POST /api/v1/stock-orders`

### 헤더

- `Idempotency-Key`: 필수
  - trim 후 `1..128`자
  - 허용 문자: 영문 대소문자, 숫자, `.`, `_`, `-`

### 요청 본문

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

### 동작

- 유효한 새 요청이면 새 job을 생성하고 `QUEUED` 상태를 반환한 뒤 비동기 처리를 시작한다.
- 같은 `Idempotency-Key`와 같은 요청이면 기존 job을 반환한다.
- replay 응답의 `status`는 기존 job의 현재 상태다.
- 같은 `Idempotency-Key`와 다른 요청이면 `409 Conflict`를 반환한다.

### 응답

- `202 Accepted`: 새 job 생성
- `200 OK`: 같은 요청의 idempotent replay
- `400 Bad Request`: `Idempotency-Key` 누락, 형식 오류, 요청 형식 오류
- `409 Conflict`: 같은 `Idempotency-Key`에 다른 요청 본문 사용

```json
{
  "jobId": "order_01HZY6J2K6K6M3VY7R4G2T0K9A",
  "status": "QUEUED",
  "createdAt": "2026-05-07T06:00:00Z"
}
```

## 작업 상태 조회

`GET /api/v1/stock-orders/{jobId}`

### 응답

- `200 OK`: 작업 상태 반환
- `404 Not Found`: 존재하지 않거나 보존 기간이 지난 작업

```json
{
  "jobId": "order_01HZY6J2K6K6M3VY7R4G2T0K9A",
  "status": "PROCESSING",
  "brokerageCode": "KIS",
  "accountNumberMasked": "*******8-01",
  "symbol": "005930",
  "side": "BUY",
  "orderType": "LIMIT",
  "quantity": 10,
  "price": 70000,
  "attemptCount": 1,
  "brokerageOrderId": "br-123",
  "executionStatus": "PENDING",
  "filledQuantity": 0,
  "remainingQuantity": 10,
  "averageExecutedPrice": null,
  "createdAt": "2026-05-07T06:00:00Z",
  "updatedAt": "2026-05-07T06:00:03Z",
  "completedAt": null,
  "expiresAt": null,
  "error": null
}
```

## 작업 결과 조회

`GET /api/v1/stock-orders/{jobId}/result`

### 응답 규칙

- `200 OK`: terminal job 결과 또는 실패 정보 반환
- `409 Conflict`: 아직 terminal state가 아닌 작업
- `404 Not Found`: 존재하지 않거나 보존 기간이 지난 작업

### 성공 예시

```json
{
  "jobId": "order_01HZY6J2K6K6M3VY7R4G2T0K9A",
  "status": "SUCCEEDED",
  "brokerageCode": "KIS",
  "accountNumberMasked": "*******8-01",
  "symbol": "005930",
  "side": "BUY",
  "orderType": "LIMIT",
  "quantity": 10,
  "price": 70000,
  "brokerageOrderId": "br-123",
  "executionStatus": "FILLED",
  "filledQuantity": 10,
  "remainingQuantity": 0,
  "averageExecutedPrice": 69950,
  "completedAt": "2026-05-07T06:00:15Z",
  "expiresAt": "2026-05-14T06:00:15Z",
  "error": null
}
```

### 실패 예시

```json
{
  "jobId": "order_01HZY6J2K6K6M3VY7R4G2T0K9A",
  "status": "FAILED",
  "brokerageCode": "KIS",
  "accountNumberMasked": "*******8-01",
  "symbol": "005930",
  "side": "BUY",
  "orderType": "LIMIT",
  "quantity": 10,
  "price": 70000,
  "brokerageOrderId": "br-123",
  "executionStatus": "REJECTED",
  "filledQuantity": 0,
  "remainingQuantity": 10,
  "averageExecutedPrice": null,
  "completedAt": "2026-05-07T06:01:12Z",
  "expiresAt": "2026-05-14T06:01:12Z",
  "error": {
    "code": "BROKERAGE_ORDER_REJECTED",
    "message": "Price band violation"
  }
}
```

## 작업 목록 조회

`GET /api/v1/stock-orders`

### Query Parameter

- `page`: 기본값 `0`
- `size`: 기본값 `20`, 최대 `100`
- `status`: 선택, 단일 상태 필터

### 정렬

- `createdAt DESC`
- `jobId DESC`

## 오류 응답 모델

모든 오류 응답은 아래 형식을 사용한다.

```json
{
  "code": "IDEMPOTENCY_KEY_CONFLICT",
  "message": "The same Idempotency-Key was used with a different request body"
}
```

대표 오류 코드는 다음과 같다.

- `MISSING_IDEMPOTENCY_KEY`
- `INVALID_IDEMPOTENCY_KEY`
- `INVALID_REQUEST`
- `IDEMPOTENCY_KEY_CONFLICT`
- `JOB_NOT_FOUND`
- `RESULT_NOT_READY`
- `BROKERAGE_TIMEOUT`
- `BROKERAGE_UNAVAILABLE`
- `BROKERAGE_AUTH_FAILED`
- `BROKERAGE_BAD_REQUEST`
- `BROKERAGE_ORDER_REJECTED`
- `BROKERAGE_ORDER_CANCELLED`
- `MAX_ATTEMPTS_EXCEEDED`
- `INTERNAL_ERROR`

## 외부 증권사 API와의 관계

- 공개 API는 증권사별 raw payload를 그대로 노출하지 않고 정규화된 주문 상태를 사용한다.
- 내부 연동 기본 경로는 `/oauth2/token`, `/v1/orders`, `/v1/orders/{orderId}`다.
- 토큰은 실제 job 처리 시점에 lazy issuance 한다.
- `401` 응답 시 cached token을 폐기하고 1회 재발급 후 재시도한다.
- 증권사 API base URL과 path는 환경 변수로 주입한다.

## 관련 문서

- `docs/REQUIREMENTS.md`
- `docs/decisions/015-java-codebase-and-brokerage-order-integration.md`
- `docs/runbook/DEBUG.md`
