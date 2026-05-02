# assignment

## 빠른 시작

- 테스트 실행: `./gradlew test`
- 로컬 실행: `./gradlew bootRun`

## 프로젝트 성격

이 프로젝트는 `backend-module` 템플릿의 `std` 레벨 협업 구조를 적용한 Spring Boot 기반 백엔드 모듈입니다.

## 설계 요약

이 과제는 동기식 이미지 처리 프록시가 아니라, 외부 Mock Worker를 대상으로 작업 수명주기를 관리하는 비동기 오케스트레이션 서버로 해석합니다.
작업 접수와 실제 처리 완료를 분리하고, 클라이언트는 작업 상태, 결과, 목록을 조회하는 방식으로 상호작용합니다.

현재 채택한 핵심 정책은 다음과 같습니다.

- API 계약:
  - 작업 접수 API는 `POST /api/v1/image-jobs`를 사용합니다.
  - 요청 형식은 `application/json`이며 이미지 입력은 `image.type`, `image.value` 구조를 사용합니다.
  - `image.type`은 `URL`, `BASE64`만 허용하고 `multipart/form-data`는 제외합니다.
- 저장소와 실행 방식:
  - 작업 메타데이터와 결과는 RDBMS에 저장합니다.
  - 별도 메시지 큐 없이 DB-backed queue와 scheduler 기반 비동기 실행을 사용합니다.
  - 로컬/테스트는 H2 file DB, 컨테이너 환경은 PostgreSQL을 기준으로 설계합니다.
- 상태 모델:
  - 작업 상태는 `QUEUED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`, `FAILED` 5개로 정의합니다.
  - `SUCCEEDED`, `FAILED`는 terminal state이며 재시도 가능한 실패만 `RETRY_SCHEDULED`로 이동합니다.
- 중복 요청 처리:
  - 작업 접수에는 `Idempotency-Key` 헤더를 필수로 요구합니다.
  - 동일 요청 판단 기준은 `(Idempotency-Key, requestHash)`입니다.
  - 같은 key와 같은 요청이면 기존 job을 반환하고, 같은 key에 다른 요청이면 `409 Conflict`를 반환합니다.
- 처리 보장과 복구:
  - 내부 처리 보장 모델은 `at-least-once`입니다.
  - 외부 Worker 호출은 장애 경계에서 중복 실행될 수 있으므로, lease 기반 복구와 제한된 재시도 정책으로 일관성을 유지합니다.
  - stale `PROCESSING` 작업은 `leasedUntil` 기준으로 복구합니다.
- 외부 Worker 연동:
  - Mock Worker API Key는 코드에 하드코딩하지 않고 설정값과 런타임 메모리 캐시로만 관리합니다.
  - 최초 호출 시 key를 발급하고, `401` 응답 시 key를 폐기한 뒤 1회 재발급 후 즉시 재시도합니다.
- 결과 보존과 목록 조회:
  - terminal job 결과는 완료 시각 기준 7일 보존합니다.
  - 목록 조회 API는 `GET /api/v1/image-jobs`이며 `createdAt DESC`, `jobId DESC` 정렬, `page`, `size`, `status` 필터를 사용합니다.

세부 근거와 예외 규칙은 `docs/decisions/`의 ADR에 정리합니다.

## 협업 산출물

- 요구사항: `docs/REQUIREMENTS.md`
- 의사결정 기록: `docs/decisions/`
- 주요 ADR:
  - `001-async-job-orchestration.md`
  - `002-api-contract-and-image-input.md`
  - `003-persistence-and-async-execution.md`
  - `004-job-state-and-retry-policy.md`
  - `005-idempotency-and-duplicate-requests.md`
  - `006-result-retention-and-list-policy.md`
  - `007-processing-guarantee-and-api-key-lifecycle.md`
- 프롬프트: `prompts/`
- 실행/장애 가이드: `docs/runbook/DEBUG.md`
- API 문서 기준: `docs/api/README.md`
- 호환성 정책: `docs/compat/README.md`
