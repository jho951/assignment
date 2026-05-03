# assignment

## 빠른 시작

- 테스트 실행: `./gradlew test`
- 로컬 실행: `./gradlew bootRun`
- 컨테이너 실행: `docker compose up --build`

## 프로젝트 성격

이 프로젝트는 `backend-module` 템플릿의 `std` 레벨 협업 구조를 적용한 Spring Boot 기반 백엔드 모듈입니다.

## 설계 요약

이 과제는 동기식 이미지 처리 프록시가 아니라, 외부 Mock Worker를 대상으로 작업 수명주기를 관리하는 비동기 오케스트레이션 서버로 해석합니다.
작업 접수와 실제 처리 완료를 분리하고, 클라이언트는 작업 상태, 결과, 목록을 조회하는 방식으로 상호작용합니다.

현재 채택한 핵심 정책은 다음과 같습니다.

- API 계약:
  - 작업 접수 API는 `POST /api/v1/image-jobs`를 사용합니다.
  - 작업 상태 조회, 결과 조회, 목록 조회 API는 각각 `GET /api/v1/image-jobs/{jobId}`, `GET /api/v1/image-jobs/{jobId}/result`, `GET /api/v1/image-jobs`를 사용합니다.
  - 요청 형식은 `application/json`이며 작업 생성 요청은 `imageUrl` 단일 필드를 사용합니다.
  - `imageUrl`은 `http`, `https`만 허용하고 `multipart/form-data`와 BASE64 업로드는 제외합니다.
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
  - 애플리케이션 startup 시 Worker API Key를 미리 발급하지 않고, 최초 job 처리 시 lazy하게 발급합니다.
  - 최초 호출 시 key를 발급하고, `401` 응답 시 key를 폐기한 뒤 1회 재발급 후 즉시 재시도합니다.
  - timeout은 5초, 일반 재시도 상한은 3회이며 Worker 장애는 job 상태와 오류 코드로 표현합니다.
- 결과 보존과 목록 조회:
  - terminal job 결과는 완료 시각 기준 7일 보존합니다.
  - 목록 조회 API는 `GET /api/v1/image-jobs`이며 `createdAt DESC`, `jobId DESC` 정렬, `page`, `size`, `status` 필터를 사용합니다.

## 런타임 구성

- 애플리케이션 이미지는 `Dockerfile`로 빌드합니다.
- 로컬 실행 기본 구성은 `compose.yaml` 기준 `app + PostgreSQL`입니다.
- Mock Worker는 저장소에 포함되지 않은 외부 제공 서비스로 가정합니다.
- Mock Worker 주소는 `WORKER_BASE_URL` 환경 변수로 주입합니다.
- Worker가 꺼져 있어도 애플리케이션은 기동되어야 하며, 실제 job 처리 시점에만 Worker 연결이 필요합니다.
- 기본 Worker base URL은 `https://dev.realteeth.ai/mock`입니다.

## 실행 방법

### 로컬 JVM 실행

- 기본 포트: `8080`
- 기본 로컬 DB: `jdbc:h2:file:./data/assignment`
- 실행 명령:
  - `./gradlew bootRun`

### 컨테이너 실행

- 시작:
  - `docker compose up --build`
- 중지:
  - `docker compose down`
- 데이터까지 정리:
  - `docker compose down -v`
- 서비스 포트:
  - 앱: `localhost:8080`
  - PostgreSQL: `localhost:5432`
- compose 기본 DB 접속 정보:
  - database: `assignment`
  - username: `assignment`
  - password: `assignment`
- Mock Worker는 compose에 포함되지 않습니다.
- `.env.example`을 참고해 `.env`를 만들면 Worker 관련 환경 변수를 쉽게 덮어쓸 수 있습니다.

## 주요 환경 변수

- `WORKER_BASE_URL`: 외부 Mock Worker base URL
- `WORKER_CANDIDATE_NAME`: API Key 발급용 candidate name
- `WORKER_CANDIDATE_EMAIL`: API Key 발급용 email
- `SPRING_DATASOURCE_URL`: PostgreSQL 또는 로컬 DB 연결 주소
- `SPRING_DATASOURCE_USERNAME`: DB 계정
- `SPRING_DATASOURCE_PASSWORD`: DB 비밀번호

## Mock Worker 참조 문서

- Base URL: `https://dev.realteeth.ai/mock`
- Swagger UI: `https://dev.realteeth.ai/mock/docs`
- OpenAPI JSON: `https://dev.realteeth.ai/mock/openapi.json`
- 우리 서버는 위 Mock Worker 문서를 기준으로 내부 연동합니다.

## 우리 서버 Swagger

- 공개 API 테스트용 OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- 공개 API 테스트용 Swagger UI: `http://localhost:8080/swagger-ui.html`
- 이 Swagger는 `POST /api/v1/image-jobs`, `GET /api/v1/image-jobs/{jobId}`, `GET /api/v1/image-jobs/{jobId}/result`, `GET /api/v1/image-jobs` 같은 우리 서버 공개 API를 테스트하기 위한 것입니다.
- Mock Worker Swagger와는 별개이며, Worker 스펙 확인은 계속 `https://dev.realteeth.ai/mock/docs`를 사용합니다.

## 검증 범위

- `./gradlew test`는 현재 통과합니다.
- 실제 Mock Worker live 호출은 외부 서비스 상태와 네트워크에 영향을 받습니다.
- Worker가 꺼져 있어도 앱 기동과 공개 API 테스트는 가능합니다.

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
  - `008-worker-integration-and-retry.md`
  - `009-container-and-runtime-config.md`
- 프롬프트: `prompts/`
- 실행/장애 가이드: `docs/runbook/DEBUG.md`
- API 문서 기준: `docs/api/README.md`
- 호환성 정책: `docs/compat/README.md`
