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

### In-progress poll 재현

1. Worker가 `startProcess` 또는 `getProcessStatus`에서 계속 `PROCESSING`을 반환하도록 준비한다.
2. 작업을 생성한다.
3. 한 번의 scheduler execution 이 remote call 한 번만 수행하고 종료하는지 확인한다.
4. job status 가 `PROCESSING`을 유지한 채 `leaseUntil = null`, `nextAttemptAt = now + pollInterval` 로 갱신되는지 확인한다.
5. 다음 scheduler tick 에 기존 `externalJobId` polling 이 다시 재개되는지 확인한다.

### Idempotency 동시 요청 검증

1. `./gradlew test --tests io.github.jho951.assignment.job.web.ImageJobConcurrencyIntegrationTests`를 실행한다.
2. 같은 `Idempotency-Key`로 동시에 여러 `POST /api/v1/image-jobs` 요청이 발생하는지 테스트 이름으로 확인한다.
3. 테스트가 `202 Accepted` 1건, `200 OK` replay 나머지, 단일 DB row, 동일 `jobId` 수렴을 검증하는지 확인한다.

### JobProcessor 경로 검증

1. `./gradlew test --tests io.github.jho951.assignment.job.processing.JobProcessorTests`를 실행한다.
2. due job 조회, claim, Worker success/failure, in-progress reschedule, retry/backoff, interrupt path가 모두 검증되는지 테스트 이름으로 확인한다.

### RestWorkerClient 경로 검증

1. `./gradlew test --tests io.github.jho951.assignment.job.worker.RestWorkerClientTests`를 실행한다.
2. API key issuance, cached key reuse, `401` refresh retry, invalid response, 4xx/5xx/timeout mapping이 모두 검증되는지 확인한다.

### Coverage 리포트 생성

1. `./gradlew test jacocoTestReport`를 실행한다.
2. HTML 리포트는 `build/reports/jacoco/test/html/index.html`에서 확인한다.
3. XML 요약은 `build/reports/jacoco/test/jacocoTestReport.xml`에서 확인한다.
4. service, processor, scheduler, configuration 경계의 미커버 분기를 우선 보강한다.

### Terminal expiry 가시성 검증

1. terminal job 하나를 만들고 `expiresAt`을 현재 시각보다 과거로 조정한다.
2. cleanup scheduler를 기다리지 않고 바로 `GET /api/v1/image-jobs/{jobId}`를 호출한다.
3. 응답이 `404 Not Found`와 `JOB_NOT_FOUND`인지 확인한다.
4. `GET /api/v1/image-jobs/{jobId}/result`도 같은 방식으로 `404`인지 확인한다.
5. `GET /api/v1/image-jobs`와 `GET /api/v1/image-jobs?status=SUCCEEDED`에서 해당 job이 즉시 제외되는지 확인한다.

### Kotlin 마이그레이션 점검

1. Kotlin plugin/dependency 추가 직후 `./gradlew test`를 실행해 mixed Java/Kotlin compile 이 깨지지 않는지 확인한다.
2. Spring bean 생성 오류나 CGLIB proxy 오류가 나면 Kotlin Spring plugin 적용 여부와 final class 여부를 확인한다.
3. JPA entity instantiation 오류가 나면 Kotlin JPA plugin, no-arg/open 처리, entity constructor visibility 를 확인한다.
4. 요청 바인딩에서 `400` 또는 `MissingKotlinParameterException`이 나면 `jackson-module-kotlin`, nullable type 선언, DTO validation annotation target(`@field:`)을 확인한다.
5. mixed Java/Kotlin 단계에서 Kotlin compile 이 Java Lombok class 의 getter/property 를 못 찾으면 Kotlin Lombok plugin 적용 여부를 확인한다. 현재 main source 는 Kotlin 완료 상태라 기본 branch 에서는 이 단계가 필요하지 않다.
6. mixed 단계에서 `compileTestJava`가 Kotlin class constructor 나 boolean getter 를 못 찾으면 Java interop shape(`@JvmRecord`, secondary constructor, `isXxx` getter)를 확인한다.
7. Kotlin 테스트에서 Mockito matcher 사용 시 `NullPointerException`이나 `InvalidUseOfMatchersException`이 나면 raw `any()/eq()`를 줄이고 exact argument stubbing, non-null matcher(`anyList()` 등), 또는 간단한 concrete stub 객체로 바꾼다.
8. Mockito 가 Kotlin concrete class 를 mock 하지 못하면 final class 대응 전략 적용 여부를 확인하거나 interface mocking 으로 되돌린다.
9. 마이그레이션 배치가 끝날 때마다 `./gradlew test jacocoTestReport`를 다시 실행하고 controller/service/processor/worker regression 을 확인한다.
10. `compileJava`가 `NO-SOURCE`로 나오면 main application source 의 Java -> Kotlin 전환은 완료된 상태로 본다.
11. `compileTestJava`가 `NO-SOURCE`로 나오면 test source 의 Java -> Kotlin 전환도 완료된 상태로 본다.

## 확인할 로그

- Worker API Key lazy issuance 시도
- `POST https://dev.realteeth.ai/mock/mock_2e80116bf37a4505aac959068b5ca051` 또는 현재 설정된 `WORKER_ISSUE_KEY_PATH` 상대 path 성공/실패
- `POST https://dev.realteeth.ai/mock/process` 또는 상대 path `/process` timeout, 4xx, 5xx, 네트워크 오류
- job 상태 전이: `QUEUED -> PROCESSING -> RETRY_SCHEDULED|FAILED|SUCCEEDED`
- remote `PROCESSING` 응답 시 lease가 해제되고 다음 poll 시점으로 재스케줄되는지
- `leaseUntil` 기반 복구 처리

## 자주 발생하는 장애

- Docker image build 실패 또는 base image pull 실패
- PostgreSQL healthcheck 지연으로 app 시작이 늦는 경우
- `WORKER_BASE_URL` 오설정으로 인한 연결 실패
- `WORKER_BASE_URL=https://dev.realteeth.ai/mock`인데 최종 호출 URL에서 `/mock`가 빠져 `403` 또는 `404`가 나는 경우
- Worker `401`으로 인한 API Key 재발급 반복
- timeout 누적으로 인한 `MAX_ATTEMPTS_EXCEEDED`
- terminal job 만료 이후 cleanup 전에도 즉시 `404 Not Found`

## 복구 절차

1. `WORKER_BASE_URL`, candidate identity, DB 연결 설정을 확인한다.
2. 최종 호출 URL이 현재 `WORKER_ISSUE_KEY_PATH` 설정값과 합쳐진 auth URL, `https://dev.realteeth.ai/mock/process`로 나가는지 확인한다.
3. compose 실행 중이면 `postgres` 컨테이너 health 상태와 앱 환경 변수 주입 값을 함께 확인한다.
4. Worker 장애가 해소되면 scheduler가 `RETRY_SCHEDULED` 작업을 다시 집행하는지 확인한다.
5. `FAILED`로 종료된 job은 정책상 자동 복구되지 않으므로 재요청이 필요한지 판단한다.
6. stale `PROCESSING` job이 있으면 lease 만료 후 복구 로직이 `RETRY_SCHEDULED` 또는 `FAILED`로 정리하는지 확인한다.
7. executor interruption 또는 장시간 `PROCESSING`이 있었다면 `attemptCount`, `nextAttemptAt`, `externalJobId`가 다음 attempt에 맞게 유지되는지 확인한다.
