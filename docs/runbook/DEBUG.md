# 디버그 런북

## 로컬 재현 절차

### 컨테이너 기동 확인

1. `.env.example`을 참고해 필요하면 `.env`를 만든다.
2. `docker compose up --build`로 `app`, `postgres`를 기동한다.
3. `http://localhost:8080/actuator/health` 또는 `http://localhost:8080/swagger-ui.html`로 앱 기동을 확인한다.
4. 컨테이너 정리는 `docker compose down`, 데이터까지 지우려면 `docker compose down -v`를 사용한다.

### 우리 서버 Swagger 확인

1. `./gradlew bootRun`으로 애플리케이션을 기동한다.
2. 브라우저에서 `http://localhost:8080/swagger-ui.html`을 연다.
3. `POST /api/v1/image-jobs`에 `Idempotency-Key` 헤더와 `imageUrl` 본문을 넣어 요청한다.
4. 생성된 `jobId`로 `GET /api/v1/image-jobs/{jobId}`와 `GET /api/v1/image-jobs/{jobId}/result`를 테스트한다.
5. OpenAPI 원문이 필요하면 `http://localhost:8080/v3/api-docs`를 확인한다.

### Mock Worker 문서 확인

1. Worker 스펙이 필요하면 `https://dev.realteeth.ai/mock/docs` 또는 `https://dev.realteeth.ai/mock/openapi.json`를 확인한다.
2. 애플리케이션은 `WORKER_BASE_URL=https://dev.realteeth.ai/mock` 기준으로 Worker를 호출하는지 설정을 점검한다.

### Worker 미연결 상태

1. `WORKER_BASE_URL`을 응답하지 않는 주소로 설정한다.
2. 애플리케이션을 기동한다.
3. 애플리케이션이 startup 실패 없이 올라오는지 확인한다.
4. `POST /api/v1/image-jobs`로 작업을 생성한다.
5. scheduler와 processor가 job을 `RETRY_SCHEDULED` 또는 `FAILED`로 전이하는지 확인한다.

### Worker 인증 실패 상태

1. 잘못된 candidate identity 또는 만료된 key를 강제로 사용하도록 설정한다.
2. 작업을 생성한다.
3. 첫 Worker 호출에서 `401`이 발생하는지 확인한다.
4. key 폐기 후 재발급 1회가 수행되는지 확인한다.
5. 재발급 이후에도 실패하면 `WORKER_AUTH_FAILED` 또는 최종 실패 상태가 기록되는지 확인한다.

### Timeout 재현

1. 응답이 5초를 넘는 Worker 또는 네트워크 지연 환경을 준비한다.
2. 작업을 생성한다.
3. timeout 발생 후 `WORKER_TIMEOUT`이 기록되는지 확인한다.
4. 재시도 지연이 `2초`, `10초` 순서로 반영되는지 확인한다.

## 확인할 로그

- Worker API Key lazy issuance 시도
- `POST https://dev.realteeth.ai/mock/auth/issue-key` 또는 상대 path `/auth/issue-key` 성공/실패
- `POST https://dev.realteeth.ai/mock/process` 또는 상대 path `/process` timeout, 4xx, 5xx, 네트워크 오류
- job 상태 전이: `QUEUED -> PROCESSING -> RETRY_SCHEDULED|FAILED|SUCCEEDED`
- `leasedUntil` 기반 복구 처리

## 자주 발생하는 장애

- Docker image build 실패 또는 base image pull 실패
- PostgreSQL healthcheck 지연으로 app 시작이 늦는 경우
- `WORKER_BASE_URL` 오설정으로 인한 연결 실패
- `WORKER_BASE_URL=https://dev.realteeth.ai/mock`인데 최종 호출 URL에서 `/mock`가 빠져 `403` 또는 `404`가 나는 경우
- Worker `401`으로 인한 API Key 재발급 반복
- timeout 누적으로 인한 `MAX_ATTEMPTS_EXCEEDED`
- terminal job 만료 이후 `404 Not Found`

## 복구 절차

1. `WORKER_BASE_URL`, candidate identity, DB 연결 설정을 확인한다.
2. 최종 호출 URL이 `https://dev.realteeth.ai/mock/auth/issue-key`, `https://dev.realteeth.ai/mock/process`로 나가는지 확인한다.
3. compose 실행 중이면 `postgres` 컨테이너 health 상태와 앱 환경 변수 주입 값을 함께 확인한다.
4. Worker 장애가 해소되면 scheduler가 `RETRY_SCHEDULED` 작업을 다시 집행하는지 확인한다.
5. `FAILED`로 종료된 job은 정책상 자동 복구되지 않으므로 재요청이 필요한지 판단한다.
6. stale `PROCESSING` job이 있으면 lease 만료 후 복구 로직이 `RETRY_SCHEDULED` 또는 `FAILED`로 정리하는지 확인한다.
