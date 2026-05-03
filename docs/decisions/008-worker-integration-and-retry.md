# ADR 008: Mock Worker 연동과 재시도 정책

## 상태

채택

## 배경

Mock Worker는 외부에서 제공되는 서비스이며 지연, timeout, 5xx, 인증 오류가 발생할 수 있다.
또한 Worker가 일시적으로 꺼져 있어도 애플리케이션 자체는 기동 가능해야 한다.
따라서 Worker 연동은 애플리케이션 startup 경로와 분리하고, 실패를 job 상태로 표현할 수 있어야 한다.

## 결정

- Worker base URL은 `WORKER_BASE_URL` 환경 변수로 주입한다.
- Worker API Key 발급에 필요한 candidate identity는 환경 변수 또는 설정으로 주입한다.
- 애플리케이션 시작 시 Worker API Key를 강제로 발급받지 않는다.
- Worker API Key는 실제 Worker 호출 시점에 lazy issuance 한다.
- 기본 설정 기준 최종 발급 URL은 `https://dev.realteeth.ai/mock/auth/issue-key`다.
- 구현에서는 `WORKER_BASE_URL` 뒤에 상대 path를 안전하게 append해서 최종 URL이 항상 `/mock/...`를 유지하도록 한다.
- 발급받은 API Key는 애플리케이션 인스턴스 메모리에 캐시한다.
- Worker 호출에는 항상 `X-API-KEY` 헤더를 포함한다.
- 기본 설정 기준 최종 처리 URL은 `https://dev.realteeth.ai/mock/process`다.
- 구현에서는 `WORKER_BASE_URL` 뒤에 상대 path `/process`, `/process/{jobId}`를 안전하게 append한다.
- Worker HTTP 호출 timeout은 5초로 둔다.
- timeout, 5xx, 429, 네트워크 오류는 재시도 대상이다.
- `401`을 제외한 4xx는 기본적으로 즉시 실패 처리한다.
- `401`은 현재 key를 폐기하고 재발급 후 1회 즉시 재시도할 수 있다.
- 일반 재시도 정책은 총 시도 횟수 `maxAttempts = 3`을 사용한다.
- 재시도 지연 정책은 `2초 -> 10초 -> 30초` backoff sequence를 사용한다.
- Worker가 응답하지 않거나 인증에 실패해도 애플리케이션은 계속 실행되고, 실패는 해당 job 상태와 오류 코드로 표현한다.

## 실패 코드

- `WORKER_TIMEOUT`
- `WORKER_UNAVAILABLE`
- `WORKER_AUTH_FAILED`
- `WORKER_BAD_REQUEST`
- `MAX_ATTEMPTS_EXCEEDED`
- `INTERNAL_ERROR`

## 이유

- startup 시 외부 Worker 의존성을 제거하면 Worker 장애가 애플리케이션 가용성으로 번지지 않는다.
- lazy API Key issuance는 실제 Worker 사용 시점에만 외부 의존성을 활성화해 운영 단순성을 높인다.
- timeout과 제한된 retry 정책은 장시간 hang과 무한 재시도를 방지한다.
- failure code를 고정하면 API 응답, 로그, 테스트 기준을 일관되게 유지할 수 있다.

## 영향

- 장점:
  - Worker가 꺼져 있어도 애플리케이션은 기동된다.
  - Worker 장애가 job 단위 실패로 격리된다.
  - 재시도 규칙과 인증 갱신 규칙이 명확하다.
- 단점:
  - 메모리 캐시 key는 인스턴스 재시작 시 재발급된다.
  - `maxAttempts = 3`과 backoff sequence `2초 -> 10초 -> 30초` 중 마지막 값은 현재 시도 상한에서는 바로 쓰이지 않을 수 있다.
- 향후 개선:
  - Worker가 idempotency token을 지원하면 외부 중복 실행 위험을 더 줄일 수 있다.
  - 다중 인스턴스 환경에서는 shared cache 또는 secret store로 key lifecycle을 통합할 수 있다.
  - `WORKER_BASE_URL`에 `/mock`가 포함된 상태에서 path resolution이 잘못되면 `403` 또는 `404`가 날 수 있으므로 URL 조합 테스트를 유지한다.
