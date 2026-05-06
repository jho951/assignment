# 요구사항

## 문제 정의

- 클라이언트는 이미지 처리 요청을 지원자가 구현한 서버로 보낸다.
- 서버는 실제 이미지 처리를 외부 서비스인 Mock Worker에 위임한다.
- Mock Worker는 지연 시간이 크고 안정성이 변동될 수 있는 무거운 작업 환경으로 가정한다.
- 클라이언트는 작업의 진행 상태, 최종 결과, 작업 목록을 조회할 수 있어야 한다.

## 범위

- 포함:
  - Spring Boot 기반 백엔드 서버 구현
  - 이미지 처리 작업 접수 API
  - 작업 식별자 발급 및 추적 가능한 조회 API
  - 작업 상태 조회, 결과 조회, 목록 조회
  - Mock Worker API Key 발급 및 이미지 처리 연동
  - 실패 표현, 중복 요청 처리, 상태 전이, 처리 보장, 재시작 동작 정의
  - 테스트 코드, 컨테이너 실행 환경, README 설계/실행 문서 작성
- 제외:
  - 프론트엔드 구현
  - Mock Worker 내부 로직 수정
  - 실제 AI 모델/GPU 처리 구현
  - 상용 계정이나 유료 자격 증명을 요구하는 구성
  - 요구사항에 명시되지 않은 고급 사용자 인증/권한 모델

## 기능 요구사항

- 작업 접수:
  - 이미지 처리 요청을 받는 API를 제공해야 한다.
  - 클라이언트가 이후 조회에 사용할 작업 식별자를 반환해야 한다.
  - 이미지 데이터의 표현 방식은 구현자가 설계한다.
- 작업 상태 조회:
  - 클라이언트가 작업 진행 상황을 확인할 수 있어야 한다.
  - 상태 모델은 직접 정의해야 하며 각 상태의 의미가 문서화되어야 한다.
- 작업 결과 조회:
  - 완료된 작업의 결과를 조회할 수 있어야 한다.
  - 실패한 작업은 실패 사실과 원인을 식별 가능하게 표현해야 한다.
  - 결과 보존 기간이 지난 terminal job은 cleanup 이전이라도 조회 시 `404 Not Found`로 취급되어야 한다.
- 작업 목록 조회:
  - 클라이언트가 작업 목록을 조회할 수 있어야 한다.
  - 정렬, 페이징, 필터링 정책은 설계자가 정하되 README에 명시해야 한다.
  - 보존 기간이 지난 terminal job은 cleanup 이전이라도 목록에서 제외되어야 한다.
- 외부 연동:
  - `POST /mock/auth/issue-key`를 사용해 API Key를 발급받아야 한다.
  - `POST /mock/process` 호출 시 `X-API-KEY` 헤더를 포함해야 한다.
  - Mock Worker의 지연, 실패, 가용성 변동을 전제로 연동해야 한다.
- 문서화:
  - README에 설계 의도와 주요 판단 근거를 포함해야 한다.
  - 실행 방법, 포트, 필요한 환경 변수와 컨테이너 구성을 명확히 적어야 한다.

## 상태 모델 요구사항

- 전체 상태 집합을 정의해야 한다.
- 허용되는 상태 전이와 허용되지 않는 상태 전이를 명시해야 한다.
- 성공과 실패를 포함한 종료 상태를 구분해야 한다.
- 재시도나 복구가 있다면 상태 전이에 어떻게 반영되는지 설명해야 한다.

## 중복 요청 처리 요구사항

- 동일 요청이 여러 번 들어올 수 있음을 전제로 해야 한다.
- 어떤 기준으로 동일 요청을 판단할지 정의해야 한다.
- `Idempotency-Key` 누락, 형식 오류, 재사용 충돌을 어떻게 구분해 표현할지 정의해야 한다.
- 유효한 새 요청은 `QUEUED`를 반환하고 이후 비동기 처리로 넘어간다는 점을 설명해야 한다.
- 같은 `Idempotency-Key` replay는 기존 job의 현재 상태를 그대로 반환한다는 점을 설명해야 한다.
- 중복 요청에 대한 사용자 관찰 가능 동작을 설명해야 한다.
- 중복 요청 처리 전략과 데이터 정합성 영향 범위를 README에 설명해야 한다.
- 같은 `Idempotency-Key` 동시 요청 race에서도 job이 하나로 수렴하는지 검증해야 한다.

## 처리 보장 및 장애 복구 요구사항

- 시스템의 처리 보장 모델을 명시해야 한다.
- 그렇게 판단한 근거를 설명해야 한다.
- 서버 재시작 시 진행 중 작업이 어떻게 되는지 설명해야 한다.
- `PROCESSING` 상태가 무기한 지속되지 않도록 attempt 단위 종료 또는 재시도 기준을 정의해야 한다.
- 데이터 정합성이 깨질 수 있는 시점과 완화 전략을 기술해야 한다.

## 가정

- 명시된 가정:
  - Mock Worker의 엔드포인트와 요청/응답 스펙은 과제 기간 중 변경되지 않는다.
  - Mock Worker의 응답 시간과 안정성은 예측 불가능하며 변동될 수 있다.
  - 이미지 데이터 표현 방식은 구현자가 자유롭게 설계할 수 있다.
  - 평가자는 로컬 환경에서 컨테이너 기반으로 서비스를 실행한다.
- 도출된 가정:
  - 작업 접수 API는 외부 처리 완료까지 동기적으로 블로킹하지 않는 방향이 적합하다.
  - 작업 메타데이터는 제출 후 조회 가능해야 하므로 서버는 작업 이력을 보존해야 한다.
  - 사용자 인증 요구사항은 명시되지 않았으므로 기본 범위에서는 단일 신뢰 클라이언트 또는 단순 접근 모델을 가정할 수 있다.
  - 외부 워커 호출 실패는 예외가 아니라 정상적으로 설계해야 하는 운영 조건이다.

## 비기능 요구사항

- 신뢰성:
  - 순간 트래픽 증가나 Mock Worker 지연이 있어도 작업 상태가 유실되지 않도록 설계해야 한다.
  - 외부 호출 실패, 타임아웃, 중복 요청, 서버 재시작 시에도 상태 일관성을 유지해야 한다.
- 보안:
  - Mock Worker API Key를 코드에 하드코딩하지 않고 안전하게 주입하고 관리해야 한다.
  - 평가자가 별도 상용 자격 증명 없이 실행할 수 있어야 한다.
- 성능:
  - 작업 접수 및 조회 API는 장시간 외부 처리에 직접 묶이지 않아야 한다.
  - 트래픽 증가 시 병목 지점과 보호 전략(예: 동시성 제한, 큐잉, 백프레셔)을 설명해야 한다.
- 운영성:
  - 테스트 코드로 핵심 동작을 검증해야 한다.
  - `./gradlew test jacocoTestReport`로 재현 가능한 coverage report를 생성할 수 있어야 한다.
  - 같은 `Idempotency-Key` 동시 요청이 단일 job으로 수렴하는 시나리오를 통합 테스트로 유지해야 한다.
  - `JobProcessor` 핵심 로직은 due job 조회, claim, single-step Worker start/status poll, in-progress reschedule, retry/backoff, interrupt 경로를 포함해 검증해야 한다.
  - `ImageJobCommandService`, `ImageJobQueryService`, recovery/cleanup/processing scheduler, 설정 bean 경계도 단위 테스트로 유지해 커버리지 공백을 줄여야 한다.
  - `RestWorkerClient`는 API key issuance, cached key reuse, `401` refresh retry, 4xx/5xx/timeout mapping, invalid response 검증을 포함해 branch coverage를 유지해야 한다.
  - 예외 응답 경계는 `ApiException`, validation error, unexpected exception 경로를 포함해 검증해야 한다.
  - 컨테이너 환경에서 재현 가능하게 실행되어야 한다.
  - 디버깅 절차와 실행 방법이 문서화되어야 한다.

## Kotlin 마이그레이션 가드레일

- Java 구현을 Kotlin으로 옮기더라도 공개 API contract는 유지해야 한다.
- endpoint path, HTTP status, JSON field 이름, 상태 이름, error code, pagination/filter semantics 는 변경하면 안 된다.
- `Idempotency-Key` trim/검증/replay/conflict 규칙과 같은 사용자 관찰 가능 동작은 그대로 유지해야 한다.
- Worker API key lazy issuance, `401` refresh retry, timeout/backoff, single-step `PROCESSING` continuation, stale recovery semantics 는 변경하면 안 된다.
- DB table/column/index/constraint 이름과 optimistic lock, lease, expiry 관련 저장 semantics 는 유지해야 한다.
- Kotlin 전환은 big-bang 이 아니라 Java/Kotlin 혼합 상태를 허용하는 점진적 순서로 진행해야 한다.
- mixed migration 중 record-like DTO/config type 은 Java 호출부 회귀를 줄이기 위해 record-style interop 를 유지해야 한다.
- mixed migration 중 남아 있는 Java Lombok class 를 Kotlin 에서 참조해야 하면 Lombok interop build 설정도 함께 유지해야 한다.
- mixed migration 중 Java 테스트가 직접 호출하는 예외/utility/value type 은 constructor overload 와 getter naming shape 도 유지해야 한다.
- Spring proxy 와 JPA 동작을 위해 Kotlin build/runtime 설정에서 `spring`, `jpa`, reflection, Jackson Kotlin module 호환성을 보장해야 한다.
- Kotlin nullability 선언은 현재 nullable header/query param, nullable response field, nullable persistence field의 의미를 그대로 반영해야 한다.
- JPA entity 는 기본적으로 `data class`로 바꾸지 않고 일반 Kotlin class 로 유지해야 한다.
- 기존 Mockito 테스트가 concrete class mocking 에 의존하는 지점을 먼저 점검하고, final class 대응 전략 없이 한꺼번에 변환하지 않아야 한다.
- 각 마이그레이션 배치마다 `./gradlew test jacocoTestReport` 기준을 유지하고 문서와 런북을 함께 갱신해야 한다.

## 설계 설명 문서 필수 항목

- 상태 모델 설계 의도
- 실패 처리 전략
- 동시 요청 발생 시 고려 사항
- 트래픽 증가 시 병목 가능 지점
- 외부 시스템과의 연동 방식 및 선택 이유

## 결정 반영 현황

- 구현 계획 문서: `prompts/plans/001-implementation-plan.md`
- 공개 API 설계 문서: `docs/api/README.md`
- 이미지 데이터 업로드 및 전달 형식: `docs/decisions/002-api-contract-and-image-input.md`
- 작업 저장소와 큐 또는 비동기 실행 방식: `docs/decisions/003-persistence-and-async-execution.md`
- 상태 이름, 허용/비허용 상태 전이, 재시도 정책: `docs/decisions/004-job-state-and-retry-policy.md`
- 중복 요청 판별 키와 사용자 관찰 가능 동작: `docs/decisions/005-idempotency-and-duplicate-requests.md`
- 결과 보존 기간과 목록 조회 정책: `docs/decisions/006-result-retention-and-list-policy.md`
- 처리 보장 모델, 재시작 복구, API Key 수명주기: `docs/decisions/007-processing-guarantee-and-api-key-lifecycle.md`
- Worker 연동, timeout/retry, lazy API Key issuance: `docs/decisions/008-worker-integration-and-retry.md`
  - `WORKER_BASE_URL`과 Worker path가 합쳐질 때 최종 URL이 `.../mock/...`로 유지되도록 구현한다.
- 컨테이너 실행 방식과 런타임 구성: `docs/decisions/009-container-and-runtime-config.md`
  - 구현 산출물: `docker/Dockerfile`, `docker/compose.yaml`, `.env.example`
  - 컨테이너 예제는 `WORKER_ISSUE_KEY_PATH` override도 노출해 image rebuild 없이 Worker auth path를 바꿀 수 있어야 한다.
- 우리 서버 공개 API Swagger 노출과 Mock Worker 외부 OpenAPI 참조 방식: `docs/decisions/010-openapi-and-swagger-ui.md`
- Java 단계의 Lombok 사용 범위와 제거 시점: `docs/decisions/011-lombok-for-boilerplate-reduction.md`, `docs/decisions/014-kotlin-migration-strategy-and-guardrails.md`
- single-step `PROCESSING` continuation, interrupt 복구, `attemptCount` 의미: `docs/decisions/012-processing-attempt-deadline-and-interrupt-recovery.md`
- coverage report 생성 방식과 테스트 우선순위: `docs/decisions/013-coverage-reporting-and-test-focus.md`
- Kotlin 점진 마이그레이션 순서와 요구사항 보호 가드레일: `docs/decisions/014-kotlin-migration-strategy-and-guardrails.md`

## 현재 미결정 항목

- 없음. 2026-05-06 기준 main source 와 test source Kotlin 마이그레이션, Lombok 정리가 모두 완료됐다. 이후 추가 언어 전환이나 테스트 전략 조정이 필요하면 `docs/decisions/014-kotlin-migration-strategy-and-guardrails.md`와 `prompts/plans/002-kotlin-migration-plan.md`를 따른다.

## 대화 기록

- 2026-05-04 요구사항 해설, 구현 우선순위, RDBMS 선택 검토 메모: `docs/notes/2026-05-04-conversation-summary.md`
- rolling transcript: `docs/notes/conversation-log.md`
