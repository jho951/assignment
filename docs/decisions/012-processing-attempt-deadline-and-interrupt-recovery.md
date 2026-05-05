# ADR 012: Single-Step PROCESSING Polling과 Interruption 복구

## 상태

채택

## 배경

현재 구조에서 가장 큰 운영 리스크는 `PROCESSING` 상태의 job을 executor thread가 terminal status까지 붙잡고 polling하는 것이다.
이 방식은 Worker가 오래 `PROCESSING`을 유지할 때 thread 점유가 길어지고, scheduler 기반 구조의 확장성 설명도 약해진다.
또한 API에 노출하는 `attemptCount`가 "Worker start 호출 수"인지 "실제 처리 attempt 수"인지도 명확히 정의할 필요가 있다.

## 결정

- executor thread 는 한 번 claim한 job 에 대해 remote interaction 한 번만 수행한다.
- 새 job 이면 `startProcess`, 기존 `externalJobId` 가 있으면 `getProcessStatus` 한 번만 호출한다.
- remote Worker 가 `PROCESSING` 을 반환하면 local status 는 그대로 `PROCESSING` 을 유지하고, lease 를 해제한 뒤 `nextAttemptAt = now + pollInterval` 로 다음 scheduler tick 에서 이어서 poll 한다.
- executor thread가 polling 중 interruption 되면 interrupt flag를 복구한 뒤, 시도 가능 횟수가 남아 있으면 `RETRY_SCHEDULED`, 남아 있지 않으면 `FAILED`로 정리한다.
- `attemptCount`는 새 Worker start 호출 수가 아니라 scheduler가 job을 claim해 처리 또는 기존 `externalJobId` polling 재개를 시도한 횟수로 정의한다.
- `externalJobId`가 이미 기록된 job은 다음 attempt에서 새 Worker start를 보내지 않고 기존 remote job polling을 먼저 재개한다.

## 이유

- single-step polling 으로 바꾸면 executor thread 점유 시간이 짧아지고, scheduler/lease 기반 구조의 의미가 더 명확해진다.
- interruption 을 즉시 terminal failure로 고정하면 graceful shutdown 또는 재시작 복구 설명과 충돌한다.
- `attemptCount`를 claim 기준으로 정의하면 retry, stale recovery, 기존 remote job polling 재개를 하나의 일관된 숫자로 설명할 수 있다.

## 영향

- 장점:
  - executor thread 가 remote terminal status 를 기다리며 오래 점유되지 않는다.
  - shutdown, thread interruption, 장시간 remote processing 상황에서도 retry/continuation 규칙이 명확하다.
  - replay 응답과 status 조회에서 `attemptCount` 의미를 일관되게 설명할 수 있다.
- 단점:
  - 장시간 처리되는 remote job은 여러 local attempt에 걸쳐 추적될 수 있다.
  - `attemptCount`는 외부 `startProcess` 호출 횟수와 항상 같지 않다.
- 검증:
  - `JobProcessorTests`는 in-progress reschedule 과 interrupt retry 경로를 유지한다.
  - `ImageJobControllerIntegrationTests`, `ImageJobConcurrencyIntegrationTests`는 생성 응답 계약과 replay semantics를 유지한다.
