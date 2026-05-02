# ADR 007: 처리 보장 모델과 Mock Worker API Key 수명주기

## 상태

채택

## 배경

요구사항은 시스템의 처리 보장 모델, 서버 재시작 시 동작, 데이터 정합성이 깨질 수 있는 시점과 완화 전략을 설명하도록 요구한다.
또한 Mock Worker API Key를 안전하게 주입하고 관리해야 한다.
현재 ADR은 저장소, 상태, 재시도 정책은 정의했지만 처리 보장 semantics와 API Key lifecycle은 분리해 적지 않았다.

## 결정

- 작업 접수 API가 job 레코드를 영속화하고 `QUEUED` 상태를 기록한 시점부터 시스템의 내부 처리 보장 모델은 `at-least-once`로 정의한다.
- `Idempotency-Key` 제약으로 job 생성은 key 기준 exactly-once에 가깝게 보장하지만, 외부 Mock Worker 호출은 장애 경계에서 중복 실행될 수 있다.
- Worker 호출 흐름은 `QUEUE/LEASE 기록 -> 외부 호출 -> 결과 상태 기록` 순서를 따른다.
- 서버가 `PROCESSING` 상태에서 중단되면 `leasedUntil`이 지난 작업을 복구 대상으로 간주하고, 다음 scheduler 주기 또는 재시작 후 `RETRY_SCHEDULED` 또는 `FAILED`로 정리한다.
- 데이터 정합성이 흔들릴 수 있는 대표 시점은 `Mock Worker 성공 응답 수신 후 DB 상태 반영 전 프로세스 중단`, `timeout 이후 실제 Worker 완료 여부를 확인할 수 없는 경우`, `401 응답 직후 API Key 교체 중 동시 요청이 겹치는 경우`다.
- 완화 전략은 lease 기반 복구, retryable failure 한정 재시도, 시도 횟수 상한, terminal state 고정, 중복 job 생성 방지다.
- Mock Worker API Key는 코드나 저장소에 하드코딩하지 않고 애플리케이션 설정과 런타임 메모리에서만 관리한다.
- API Key 발급에 필요한 candidate identity는 환경 변수 또는 외부 설정으로 주입한다.
- API Key는 애플리케이션 인스턴스 단위로 in-memory cache에 보관하고, 최초 Worker 호출 시 lazy issuance 한다.
- `/mock/process`가 `401`을 반환하면 현재 key를 폐기하고 `POST /mock/auth/issue-key`로 1회 재발급한 뒤 같은 시도를 즉시 다시 수행한다.
- 재발급 이후에도 실패하면 일반 retry policy를 따른다.

## 이유

- 요구사항이 허용하는 범위에서 가장 현실적인 보장 모델은 at-least-once다.
- DB-backed queue와 lease 복구를 사용하면 재시작 후 작업 유실을 줄일 수 있다.
- Mock Worker가 서버 측 idempotency contract를 제공하지 않는 한 외부 호출 exactly-once는 보장할 수 없다.
- API Key를 메모리 캐시로만 관리하면 영속 저장 없이도 안전성과 단순성을 함께 확보할 수 있다.

## 영향

- 장점:
  - 처리 보장, 재시작 복구, 데이터 정합성 위험 지점을 문서로 명확히 설명할 수 있다.
  - API Key가 코드와 영속 저장소에 남지 않는다.
- 단점:
  - crash 경계에서는 같은 job이 Mock Worker에 두 번 전달될 수 있다.
  - 다중 인스턴스 환경에서는 API Key cache 동기화가 추가 과제가 된다.
- 향후 개선:
  - Worker가 지원하면 외부 호출에도 idempotency token을 전달한다.
  - 다중 인스턴스에서는 shared secret store 또는 distributed cache로 key lifecycle을 통합한다.
