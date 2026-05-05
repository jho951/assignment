# Kotlin 마이그레이션 계획

## 목표

- Java 구현을 Kotlin 으로 점진적으로 이전하되 요구사항에 명시된 API, 상태 전이, idempotency, Worker 연동, recovery semantics 를 그대로 유지한다.
- JPA, Spring proxy, Jackson binding, Mockito 테스트에서 자주 발생하는 Kotlin 전환 리스크를 배치별로 분리해 검증한다.

## 진행 메모

- 2026-05-06 batch 1 완료: Kotlin plugin/runtime 도입, `AssignmentApplication`, `JobProperties`, `WorkerProperties`, `job/web/dto` record-like type 을 Kotlin `@JvmRecord`로 전환했다.
- 2026-05-06 검증: `./gradlew test` 통과.
- 2026-05-06 batch 2 완료: `ApiException`, `ImageJobExceptionHandler`, `ImageJobController`, `RequestHashService`, `ImageJobCommandService`, `ImageJobQueryService`를 Kotlin 으로 전환했다.
- 2026-05-06 batch 2 교훈: mixed Java/Kotlin 상태에서 Java Lombok source 를 Kotlin service 가 참조하면 Kotlin Lombok plugin 이 필요하다.
- 2026-05-06 batch 2 검증: targeted service/web test 와 `./gradlew test` 통과.
- 2026-05-06 batch 3 완료: `ApplicationCoreConfiguration`, `OpenApiConfiguration`, `worker/*`, `processing/*`를 Kotlin 으로 전환했다.
- 2026-05-06 batch 3 교훈: Java 테스트가 직접 생성하는 Kotlin exception/value type 은 secondary constructor 와 boolean getter shape 를 유지해야 `compileTestJava`가 깨지지 않는다.
- 2026-05-06 batch 3 검증: targeted config/worker/processing test 와 `./gradlew test` 통과.
- 2026-05-06 batch 4 완료: `job/domain/*`, `job/repository/ImageJobRepository`를 Kotlin 으로 전환했고 main source 를 `src/main/kotlin`으로 정리했다.
- 2026-05-06 batch 4 교훈: JPA entity 는 `open class`, protected no-arg, nullable timestamp/lease field, Java test 직접 호출 메서드 shape 를 같이 지켜야 회귀가 없다.
- 2026-05-06 batch 4 검증: targeted domain/repository/processing regression 통과.
- 2026-05-06 batch 5 완료: 모든 main Java source 전환 후 `build.gradle`에서 Kotlin Lombok plugin 과 Lombok dependency 를 제거했다.
- 2026-05-06 batch 5 검증: `./gradlew test jacocoTestReport` 통과, `compileJava`는 `NO-SOURCE` 상태로 확인했다.
- 2026-05-06 batch 6 완료: `src/test/java`의 JUnit/Mockito/MockMvc/HttpClient 테스트를 `src/test/kotlin`으로 전환했다.
- 2026-05-06 batch 6 교훈: Kotlin 테스트에서는 raw Mockito `any()/eq()`가 non-null 시그니처와 충돌할 수 있어 exact argument stubbing, 구체 stub 객체, `anyList()` 같은 non-null matcher 로 정리하는 편이 안전하다.
- 2026-05-06 batch 6 검증: `./gradlew test jacocoTestReport`, `./gradlew clean test` 통과, `compileTestJava`는 `NO-SOURCE` 상태로 확인했다.
- 2026-05-06 batch 7 완료: 만료된 terminal job을 cleanup 이전에도 read path에서 즉시 숨기도록 `ImageJobQueryService`와 repository query를 보강했다.
- 2026-05-06 batch 7 검증: expired job status/result/list integration test 추가 후 `./gradlew test jacocoTestReport` 통과.

## 마이그레이션 전 고려사항

- Kotlin plugin 과 runtime dependency 를 먼저 갖추기 전에는 Spring bean 이나 JPA entity 를 변환하지 않는다.
- nullable 계약은 현재 Java 코드가 암묵적으로 허용하는 값까지 포함해 다시 정의해야 한다.
- request DTO validation annotation 은 Kotlin 에서 `@field:` target 으로 옮겨야 한다.
- record -> data class 변환 시 JSON field 이름과 생성자 파라미터 순서를 유지한다.
- JPA entity 는 `data class` 사용을 피하고 일반 class 로 유지한다.
- concrete class mocking 이 필요한 테스트는 final class 대응 전략을 먼저 정한다.
- 각 배치 종료 시 `./gradlew test jacocoTestReport`를 통과시켜야 다음 배치로 넘어간다.

## 파일 단위 체크리스트

### 1. Build 와 공통 설정

- [x] `build.gradle`: `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.spring`, `org.jetbrains.kotlin.plugin.jpa`를 추가하고 mixed 단계에서만 `org.jetbrains.kotlin.plugin.lombok`을 유지한 뒤 최종 제거한다.
- [x] `build.gradle`: `kotlin-reflect`, `jackson-module-kotlin` 추가 후 기존 Spring Boot/JPA/Testcontainers 조합과 충돌이 없는지 확인한다.
- [x] `build.gradle`: Lombok 을 즉시 제거하지 말고 남아 있는 Java class 범위에서만 유지한 뒤, main Java source 제거 후 plugin/dependency 를 정리한다.
- [x] `gradle.properties`: Kotlin version 관리가 필요하면 중앙화하고 Java 17 toolchain 은 유지한다.
- [x] `settings.gradle`: plugin resolution 추가가 필요 없음을 확인하고 변경하지 않는다.

### 2. Bootstrap 과 Configuration

- [x] `src/main/java/io/github/jho951/assignment/AssignmentApplication.java`: `src/main/kotlin/.../AssignmentApplication.kt`로 전환 후 `@ConfigurationPropertiesScan` 범위가 유지되는지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/config/ApplicationCoreConfiguration.java`: bean 이름, executor 설정, timeout 설정이 그대로 유지되는지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/config/OpenApiConfiguration.java`: OpenAPI bean 등록 이름과 Swagger 동작이 그대로 유지되는지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/config/JobProperties.java`: nested properties binding, 숫자/boolean binding, prefix `jobs`가 동일한지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/config/WorkerProperties.java`: prefix `worker`, base URL/path/timeout binding 이 동일한지 확인한다.

### 3. Web DTO 와 예외 경계

- [x] `src/main/java/io/github/jho951/assignment/job/web/dto/ImageJobCreateRequest.java`: `@field:NotBlank`, `@field:Pattern`으로 옮기고 `imageUrl` required semantics 를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/dto/ImageJobCreateResponse.java`: `createdAt` 직렬화 형식과 필드 이름을 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/dto/ImageJobStatusResponse.java`: `completedAt`, `expiresAt`, `error` nullable semantics 를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/dto/ImageJobResultResponse.java`: `result`, `completedAt`, `expiresAt`, `error` nullable semantics 를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/dto/ImageJobSummaryResponse.java`: 목록 응답의 필드 순서와 nullable semantics 를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/dto/ImageJobListResponse.java`: page/size/total metadata 타입과 이름을 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/dto/ImageJobErrorResponse.java`: error payload field 이름 `code`, `message`를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/dto/ApiErrorResponse.java`: validation/unexpected error payload 구조를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/ApiException.java`: `HttpStatus`, `JobFailureCode`, message 접근 방식이 기존 예외 핸들러와 동일하게 동작하는지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/ImageJobExceptionHandler.java`: `ApiException`, validation error, generic exception 처리 결과가 바뀌지 않는지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/job/web/ImageJobController.java`: nullable header/query param, `202/200` replay semantics, path mapping 이 동일한지 확인한다.

### 4. Service 계층

- [x] `src/main/java/io/github/jho951/assignment/job/service/RequestHashService.java`: SHA-256 입력 문자열 형식과 hex 출력이 byte-for-byte 동일한지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/job/service/ImageJobCommandService.java`: `Idempotency-Key` trim, regex, missing/conflict/replay semantics 가 그대로인지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/job/service/ImageJobQueryService.java`: sort, page/size normalization, terminal/result semantics, expiry semantics, expired terminal `404/list exclude` semantics 가 그대로인지 확인한다.

### 5. Worker 연동 계층

- [x] `src/main/java/io/github/jho951/assignment/job/worker/WorkerClient.java`: interface 유지 여부와 호출 경계를 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/job/worker/WorkerRemoteStatus.java`: enum literal 이 Worker mapping 과 동일한지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/job/worker/WorkerStartResult.java`: start 응답 contract 를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/worker/WorkerStatusResult.java`: poll 응답 contract 를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/worker/WorkerClientException.java`: retryable flag 와 failure code/message 노출 방식이 동일한지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/job/worker/RestWorkerClient.java`: URL 결합, API key issuance/cache, `401` refresh, timeout/4xx/5xx mapping, invalid payload 처리, nested DTO 직렬화가 그대로인지 확인한다.

### 6. Processing 과 Scheduler

- [x] `src/main/java/io/github/jho951/assignment/job/processing/JobProcessor.java`: due job 조회, claim, single-step start/status poll, retry/backoff, interrupt handling, success/failure semantics 를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/processing/JobProcessingScheduler.java`: recovery 호출 순서, claim 후 dispatch semantics, executor submission 경계를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/processing/JobRecoveryService.java`: stale `PROCESSING` recovery 와 `attemptCount` 기준 전이 규칙을 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/processing/JobCleanupScheduler.java`: terminal expiry cleanup semantics 와 batch 처리 기준을 유지한다.

### 7. Domain 과 Persistence

- [x] `src/main/java/io/github/jho951/assignment/job/domain/JobFailureCode.java`: enum literal 이 API error payload 와 테스트 기대값에 그대로 쓰이는지 확인한다.
- [x] `src/main/java/io/github/jho951/assignment/job/domain/JobStatus.java`: 상태 이름과 terminal 판정 규칙을 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/domain/ImageInputType.java`: enum literal 과 persistence mapping 을 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/domain/JobStatusTransitionPolicy.java`: 허용/비허용 전이 규칙과 exception message 를 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/repository/ImageJobRepository.java`: query method 이름, JPQL, 정렬 기준, 반환 타입과 expired terminal visibility filter를 요구사항에 맞게 유지한다.
- [x] `src/main/java/io/github/jho951/assignment/job/domain/ImageJob.java`: 마지막 배치에서 전환하고, table/column/index/constraint 이름, `@Version`, `@PrePersist`, `@PreUpdate`, protected no-arg, mutable field semantics 를 유지한다.

### 8. 테스트 전환

- [x] `src/test/java/io/github/jho951/assignment/AssignmentApplicationTests.java`: Spring Boot 기동 smoke test 가 Kotlin source set 전환 뒤에도 유지되는지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/config/ApplicationCoreConfigurationTests.java`: executor/rest client bean 설정이 동일한지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/config/OpenApiConfigurationTests.java`: OpenAPI bean 이 깨지지 않는지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/job/domain/ImageJobTests.java`: entity factory/timestamp/terminal semantics 를 유지한다.
- [x] `src/test/java/io/github/jho951/assignment/job/domain/JobStatusTransitionPolicyTests.java`: 전이 정책 regression 이 없는지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/job/service/RequestHashServiceTests.java`: hash 결과가 동일한지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/job/service/ImageJobCommandServiceTests.java`: replay/conflict/create semantics 가 유지되는지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/job/service/ImageJobQueryServiceTests.java`: 조회/목록/예외 semantics 와 expired terminal `404` semantics 가 유지되는지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/job/worker/WorkerClientExceptionTests.java`: 예외 contract 가 유지되는지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/job/worker/RestWorkerClientTests.java`: key issuance, `401` refresh, timeout/error mapping 분기가 그대로인지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/job/processing/JobProcessorTests.java`: claim/poll/retry/backoff/interrupt path 가 그대로인지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/job/processing/JobProcessingSchedulerTests.java`: concrete class mocking 전략이 Kotlin 에서도 유효한지 확인한다.
- [x] `src/test/java/io/github/jho951/assignment/job/processing/JobRecoveryServiceTests.java`: stale recovery semantics 를 유지한다.
- [x] `src/test/java/io/github/jho951/assignment/job/processing/JobCleanupSchedulerTests.java`: expiry cleanup semantics 를 유지한다.
- [x] `src/test/java/io/github/jho951/assignment/job/web/ImageJobExceptionHandlerTests.java`: `ApiException`, validation, generic exception 경계를 유지한다.
- [x] `src/test/java/io/github/jho951/assignment/job/web/ImageJobControllerIntegrationTests.java`: 요청/응답 contract, status code, expired terminal immediate hide semantics 를 유지한다.
- [x] `src/test/java/io/github/jho951/assignment/job/web/ImageJobConcurrencyIntegrationTests.java`: concurrent idempotency convergence 를 유지한다.

## 배치별 검증 순서

1. build/plugin 추가 후 `./gradlew test`
2. DTO/properties/service 변환 후 `./gradlew test jacocoTestReport`
3. controller/worker/processing 변환 후 targeted test 와 전체 test 재실행
4. entity/repository 변환 후 schema mapping, integration test, cleanup/recovery semantics 재검증
5. Lombok 제거 후 전체 회귀와 문서 정합성 확인
6. test source 전환 후 `./gradlew clean test`로 stale output 없이 `compileTestJava NO-SOURCE`를 확인한다
