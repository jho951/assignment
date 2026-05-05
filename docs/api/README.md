# 공개 API 가이드

이 문서는 클라이언트가 사용하는 공개 API 계약을 정의합니다.
비동기 작업 오케스트레이션 모델, 중복 요청 처리, 상태 조회, 결과 조회, 목록 조회 정책을 한 곳에서 확인할 수 있게 정리합니다.

## 설계 원칙

- 공개 API는 비동기 작업 리소스 중심으로 설계한다.
- 작업 생성과 실제 이미지 처리 완료를 분리한다.
- Mock Worker의 실제 입력 계약이 `imageUrl` 하나이므로 공개 API도 URL 기반 입력만 허용한다.
- 중복 요청 제어는 `Idempotency-Key` 헤더로 수행한다.
- 상태 조회와 결과 조회를 분리해 비완료 작업의 응답 의미를 명확히 한다.

## 리소스 모델

작업 리소스는 `image job`으로 정의한다.

- `jobId`: 서버가 발급한 작업 식별자
- `status`: `QUEUED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`, `FAILED`
- `imageUrl`: 원본 이미지 URL
- `attemptCount`: scheduler가 job을 claim해 처리 또는 기존 `externalJobId` polling 재개를 시도한 횟수
- `createdAt`: 작업 생성 시각
- `updatedAt`: 마지막 상태 변경 시각
- `completedAt`: 종료 시각, 미종료면 `null`
- `expiresAt`: terminal job 만료 시각, 미종료면 `null`
- `error`: 실패 시 노출하는 오류 정보

## 공통 규칙

- Base path: `/api/v1/image-jobs`
- Content-Type: `application/json`
- Accept: `application/json`
- 공개 API OpenAPI JSON endpoint: `/v3/api-docs`
- 공개 API Swagger UI endpoint: `/swagger-ui.html`
- 시간 필드는 RFC 3339 UTC 문자열을 사용한다.
- `imageUrl`은 `http` 또는 `https` URL만 허용한다.
- `expiresAt`이 지난 terminal job은 cleanup 이전이라도 상태/결과 조회에서 `404`, 목록 조회에서 제외한다.
- 인증/권한 모델은 과제 범위에서 제외한다.
- executor thread는 한 번 claim한 job에 대해 `startProcess` 또는 `getProcessStatus` 한 번만 수행한다.
- remote Worker가 계속 `PROCESSING`이면 job status는 `PROCESSING`을 유지한 채 lease를 해제하고 다음 poll 시점으로 되돌린다.

## 작업 생성

`POST /api/v1/image-jobs`

### 헤더

- `Idempotency-Key`: 필수
  - trim 후 `1..128`자
  - 허용 문자: 영문 대소문자, 숫자, `.`, `_`, `-`

### 요청 본문

```json
{
  "imageUrl": "https://example.com/images/input.png"
}
```

### 동작

- 유효한 새 요청이면 새 job을 생성하고 `QUEUED` 상태를 반환한 뒤 비동기 처리를 시작한다.
- 같은 `Idempotency-Key`와 같은 요청이면 기존 job을 반환한다.
- replay 응답의 `status`는 고정값이 아니라 기존 job의 현재 상태다. 예를 들어 기존 job이 `PROCESSING`이면 `PROCESSING`을 반환한다.
- 같은 `Idempotency-Key`와 다른 요청이면 잘못된 재시도 요청으로 보고 `409 Conflict`를 반환한다.
- 같은 `imageUrl`이라도 `Idempotency-Key`가 다르면 별도의 새 job을 생성한다.

### 응답

- `202 Accepted`: 새 job 생성, 응답 `status`는 `QUEUED`
- `200 OK`: 같은 요청의 idempotent replay, 응답 `status`는 기존 job의 현재 상태
- `400 Bad Request`: `Idempotency-Key` 누락, `Idempotency-Key` 형식 오류, 또는 요청 형식 오류
- `409 Conflict`: 같은 `Idempotency-Key`에 다른 요청 본문 사용

```json
{
  "jobId": "job_01HZY6J2K6K6M3VY7R4G2T0K9A",
  "status": "QUEUED",
  "createdAt": "2026-05-02T06:00:00Z"
}
```

## 작업 상태 조회

`GET /api/v1/image-jobs/{jobId}`

### 응답

- `200 OK`: 작업 상태 반환
- `404 Not Found`: 존재하지 않거나 보존 기간이 지난 작업

```json
{
  "jobId": "job_01HZY6J2K6K6M3VY7R4G2T0K9A",
  "status": "PROCESSING",
  "imageUrl": "https://example.com/images/input.png",
  "attemptCount": 1,
  "createdAt": "2026-05-02T06:00:00Z",
  "updatedAt": "2026-05-02T06:00:03Z",
  "completedAt": null,
  "expiresAt": null,
  "error": null
}
```

## 작업 결과 조회

`GET /api/v1/image-jobs/{jobId}/result`

### 응답 규칙

- `200 OK`: terminal job 결과 또는 실패 정보 반환
- `409 Conflict`: 아직 terminal state가 아닌 작업
- `404 Not Found`: 존재하지 않거나 보존 기간이 지난 작업

### 성공 예시

```json
{
  "jobId": "job_01HZY6J2K6K6M3VY7R4G2T0K9A",
  "status": "SUCCEEDED",
  "result": "https://mock-worker.example/results/output.png",
  "completedAt": "2026-05-02T06:00:15Z",
  "expiresAt": "2026-05-09T06:00:15Z",
  "error": null
}
```

### 실패 예시

```json
{
  "jobId": "job_01HZY6J2K6K6M3VY7R4G2T0K9A",
  "status": "FAILED",
  "result": null,
  "completedAt": "2026-05-02T06:01:12Z",
  "expiresAt": "2026-05-09T06:01:12Z",
  "error": {
    "code": "WORKER_TIMEOUT",
    "message": "Mock Worker did not complete within the configured timeout"
  }
}
```

## 작업 목록 조회

`GET /api/v1/image-jobs`

### Query Parameter

- `page`: 기본값 `0`
- `size`: 기본값 `20`, 최대 `100`
- `status`: 선택, 단일 상태 필터

### 정렬

- `createdAt DESC`
- `jobId DESC`

### 응답 예시

```json
{
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "items": [
    {
      "jobId": "job_01HZY6J2K6K6M3VY7R4G2T0K9A",
      "status": "SUCCEEDED",
      "imageUrl": "https://example.com/images/input.png",
      "attemptCount": 1,
      "createdAt": "2026-05-02T06:00:00Z",
      "updatedAt": "2026-05-02T06:00:15Z",
      "completedAt": "2026-05-02T06:00:15Z",
      "expiresAt": "2026-05-09T06:00:15Z",
      "error": null
    },
    {
      "jobId": "job_01HZY6HTTF9XG8Y2M7M8M0V1K",
      "status": "FAILED",
      "imageUrl": "https://example.com/images/input-2.png",
      "attemptCount": 3,
      "createdAt": "2026-05-02T05:50:00Z",
      "updatedAt": "2026-05-02T05:51:12Z",
      "completedAt": "2026-05-02T05:51:12Z",
      "expiresAt": "2026-05-09T05:51:12Z",
      "error": {
        "code": "WORKER_UNAVAILABLE",
        "message": "Mock Worker returned 500"
      }
    }
  ]
}
```

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
- `WORKER_TIMEOUT`
- `WORKER_UNAVAILABLE`
- `WORKER_AUTH_FAILED`
- `WORKER_BAD_REQUEST`
- `MAX_ATTEMPTS_EXCEEDED`
- `INTERNAL_ERROR`

## 외부 Worker와의 관계

- 공개 API는 internal status와 retry 정책을 숨기지 않고 그대로 노출한다.
- 우리 서버 공개 API 테스트는 `/swagger-ui.html`과 `/v3/api-docs`를 사용한다.
- Worker base URL은 `https://dev.realteeth.ai/mock`를 기준으로 한다.
- Worker 참조 문서는 `https://dev.realteeth.ai/mock/docs`, `https://dev.realteeth.ai/mock/openapi.json`를 사용한다.
- Worker API Key 발급은 내부에서 `POST /auth/issue-key`로 호출하며, 기본 설정 기준 최종 URL은 `https://dev.realteeth.ai/mock/auth/issue-key`다.
- Worker 시작 요청은 내부에서 `POST /process`로 변환한다.
- Worker는 `imageUrl`만 지원하므로 공개 API도 동일하게 URL 입력만 허용한다.
- Worker 처리 상태 조회와 재시도는 서버 내부 scheduler와 processor가 담당한다.
- Worker API Key는 애플리케이션 startup 시 미리 발급하지 않고, 실제 job 처리 시 lazy issuance 한다.
- Worker가 일시적으로 꺼져 있어도 공개 API 서버는 기동되며, Worker 장애는 개별 job 실패 또는 재시도로 표현한다.

## 관련 문서

- `docs/decisions/002-api-contract-and-image-input.md`
- `docs/decisions/004-job-state-and-retry-policy.md`
- `docs/decisions/005-idempotency-and-duplicate-requests.md`
- `docs/decisions/006-result-retention-and-list-policy.md`
- `docs/decisions/007-processing-guarantee-and-api-key-lifecycle.md`
- `docs/decisions/008-worker-integration-and-retry.md`
- `docs/decisions/009-container-and-runtime-config.md`
