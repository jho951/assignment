# ADR 004: 작업 상태 모델과 재시도 정책

## 상태

채택

## 배경

클라이언트는 작업 상태, 결과, 실패 원인을 조회할 수 있어야 한다.
Mock Worker는 지연과 실패 가능성이 있으므로, 상태 모델은 재시도와 최종 실패를 구분해야 한다.

## 결정

작업 상태는 다음 5개로 정의한다.

- `QUEUED`
- `PROCESSING`
- `RETRY_SCHEDULED`
- `SUCCEEDED`
- `FAILED`

허용 상태 전이는 다음과 같다.

- `QUEUED -> PROCESSING`
- `PROCESSING -> SUCCEEDED`
- `PROCESSING -> RETRY_SCHEDULED`
- `PROCESSING -> FAILED`
- `RETRY_SCHEDULED -> PROCESSING`
- `RETRY_SCHEDULED -> FAILED`

복구와 재시도는 다음 규칙으로 상태 전이에 반영한다.

- 재시도 가능한 Worker 실패는 `PROCESSING -> RETRY_SCHEDULED`로 전환한다.
- 서버 재시작 또는 scheduler 복구 시 `leasedUntil`이 지난 stale `PROCESSING` 작업은 시도 가능 횟수가 남아 있으면 `RETRY_SCHEDULED`로, 남아 있지 않으면 `FAILED`로 전환한다.
- `SUCCEEDED`, `FAILED`는 terminal state이며 이후 다른 상태로 전이하지 않는다.

비허용 상태 전이는 다음과 같다.

- `QUEUED -> SUCCEEDED`
- `QUEUED -> FAILED`
- `RETRY_SCHEDULED -> SUCCEEDED`
- `SUCCEEDED -> *`
- `FAILED -> *`
- 위에 명시되지 않은 모든 전이

재시도 정책은 다음과 같다.

- 최대 Worker 호출 시도 횟수는 3회다.
- timeout, 네트워크 오류, 5xx, 429는 재시도한다.
- `401`을 제외한 4xx는 기본적으로 최종 실패로 처리한다.
- 401은 API key 재발급 후 1회 즉시 재시도한다.
- backoff는 `2초 -> 10초 -> 30초` sequence를 사용한다.
- 최대 시도 횟수를 넘으면 `FAILED`로 전환한다.

## 이유

- 상태 수를 최소화하면서 작업 대기, 실행, 재시도, 성공, 실패를 모두 표현할 수 있다.
- 재시도 가능한 실패와 최종 실패를 명확히 구분할 수 있다.
- 종료 상태를 terminal state로 고정해 상태 일관성을 유지한다.
- lease 만료 복구도 기존 상태 집합 안에서 표현할 수 있다.

## 영향

- 장점:
  - API 응답과 내부 처리 흐름이 단순하다.
  - 테스트해야 할 상태 전이가 명확하다.
- 단점:
  - 취소, 일시정지, 만료 같은 고급 상태는 표현하지 않는다.
- 향후 개선:
  - 사용자 취소 요구사항이 생기면 `CANCELED` 상태를 추가한다.
