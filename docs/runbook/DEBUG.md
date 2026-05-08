# 디버그 런북

## 로컬 재현 절차

### 컨테이너 기동 확인

1. `.env.example`을 참고해 필요하면 `.env`를 만든다.
2. `docker compose -f docker/compose.yaml up --build -d`로 `app`, `postgres`를 기동한다.
3. `http://localhost:8080/actuator/health` 또는 `http://localhost:8080/swagger-ui.html`로 앱 기동을 확인한다.
4. 컨테이너 정리는 `docker compose -f docker/compose.yaml down`, 데이터까지 지우려면 `docker compose -f docker/compose.yaml down -v`를 사용한다.

### 우리 서버 Swagger 확인

1. `./gradlew bootRun`으로 애플리케이션을 기동한다.
2. 브라우저에서 `http://localhost:8080/swagger-ui.html`을 연다.
3. `POST /api/v1/stock-orders`에 `Idempotency-Key` 헤더와 주문 본문을 넣어 요청한다.
4. 생성된 `jobId`로 `GET /api/v1/stock-orders/{jobId}`와 `GET /api/v1/stock-orders/{jobId}/result`를 테스트한다.
5. OpenAPI 원문이 필요하면 `http://localhost:8080/v3/api-docs`를 확인한다.

### 증권사 API 미연결 상태

1. `BROKERAGE_BASE_URL`을 응답하지 않는 주소로 설정한다.
2. 애플리케이션을 기동한다.
3. 애플리케이션이 startup 실패 없이 올라오는지 확인한다.
4. `POST /api/v1/stock-orders`로 작업을 생성한다.
5. scheduler와 processor가 job을 `RETRY_SCHEDULED` 또는 `FAILED`로 전이하는지 확인한다.

### 증권사 인증 실패 상태

1. 잘못된 `BROKERAGE_APP_KEY`, `BROKERAGE_APP_SECRET`, `BROKERAGE_CLIENT_ID`를 설정한다.
2. 작업을 생성한다.
3. 첫 증권사 호출에서 `401` 또는 `403`이 발생하는지 확인한다.
4. `401`이면 cached token 폐기 후 재발급 1회가 수행되는지 확인한다.
5. 재발급 이후에도 실패하면 `BROKERAGE_AUTH_FAILED` 또는 최종 실패 상태가 기록되는지 확인한다.

### Timeout 재현

1. timeout보다 늦게 응답하는 증권사 mock/stub 서버를 준비한다.
2. `BROKERAGE_BASE_URL`을 해당 서버로 맞춘다.
3. 작업을 생성한다.
4. timeout 발생 후 `BROKERAGE_TIMEOUT`이 기록되는지 확인한다.
5. 재시도 지연이 `2초`, `10초`, `30초` 순서로 반영되는지 확인한다.

### In-progress poll 재현

1. 증권사 상태 조회가 `PENDING` 또는 `PARTIALLY_FILLED`를 계속 반환하도록 mock/stub을 준비한다.
2. 작업을 생성한다.
3. 한 번의 scheduler execution이 remote call 한 번만 수행하고 종료하는지 확인한다.
4. job status가 `PROCESSING`을 유지한 채 `leaseUntil = null`, `nextAttemptAt = now + pollInterval`로 갱신되는지 확인한다.
5. 다음 scheduler tick에서 기존 `brokerageOrderId` polling이 재개되는지 확인한다.

### 단위/통합 테스트 재현

1. `./gradlew test --tests io.github.jho951.assignment.brokerage.RestBrokerageClientTests`
2. `./gradlew test --tests io.github.jho951.assignment.order.processing.JobProcessorTests`
3. `./gradlew test --tests io.github.jho951.assignment.order.web.StockOrderControllerIntegrationTests`

### Coverage 리포트 생성

1. `./gradlew test jacocoTestReport`를 실행한다.
2. HTML 리포트는 `build/reports/jacoco/test/html/index.html`에서 확인한다.
3. XML 요약은 `build/reports/jacoco/test/jacocoTestReport.xml`에서 확인한다.

### Terminal expiry 가시성 검증

1. terminal job 하나를 만들고 `expiresAt`을 현재 시각보다 과거로 조정한다.
2. cleanup scheduler를 기다리지 않고 바로 `GET /api/v1/stock-orders/{jobId}`를 호출한다.
3. 응답이 `404 Not Found`와 `JOB_NOT_FOUND`인지 확인한다.
4. `GET /api/v1/stock-orders/{jobId}/result`도 같은 방식으로 `404`인지 확인한다.
5. `GET /api/v1/stock-orders`와 `GET /api/v1/stock-orders?status=SUCCEEDED`에서 해당 job이 즉시 제외되는지 확인한다.

## 확인할 로그

- 증권사 access token lazy issuance 시도
- `POST {BROKERAGE_BASE_URL}{BROKERAGE_TOKEN_PATH}` 성공/실패
- `POST {BROKERAGE_BASE_URL}{BROKERAGE_ORDER_PATH}` timeout, 4xx, 5xx, 네트워크 오류
- `GET {BROKERAGE_BASE_URL}{BROKERAGE_ORDER_STATUS_PATH}` poll 성공/실패
- job 상태 전이: `QUEUED -> PROCESSING -> RETRY_SCHEDULED|FAILED|SUCCEEDED`
- remote `PENDING` 또는 `PARTIALLY_FILLED` 응답 시 lease가 해제되고 다음 poll 시점으로 재스케줄되는지
- `leaseUntil` 기반 stale recovery 처리

## 자주 발생하는 장애

- Docker image build 실패 또는 base image pull 실패
- PostgreSQL healthcheck 지연으로 app 시작이 늦는 경우
- `BROKERAGE_BASE_URL` 오설정으로 인한 연결 실패
- 토큰 endpoint 또는 order path 오설정
- 증권사 `401`으로 인한 access token 재발급 반복
- timeout 누적으로 인한 `MAX_ATTEMPTS_EXCEEDED`
- terminal job 만료 이후 cleanup 전에도 즉시 `404 Not Found`

## 복구 절차

1. `BROKERAGE_BASE_URL`, path, credential, DB 연결 설정을 확인한다.
2. 최종 호출 URL이 기대한 token/order/status endpoint로 나가는지 확인한다.
3. compose 실행 중이면 `postgres` health와 앱 환경 변수 주입 값을 함께 확인한다.
4. 증권사 장애가 해소되면 scheduler가 `RETRY_SCHEDULED` 작업을 다시 집행하는지 확인한다.
5. `FAILED`로 종료된 job은 정책상 자동 복구되지 않으므로 재요청이 필요한지 판단한다.
6. stale `PROCESSING` job이 있으면 lease 만료 후 복구 로직이 `RETRY_SCHEDULED` 또는 `FAILED`로 정리하는지 확인한다.
