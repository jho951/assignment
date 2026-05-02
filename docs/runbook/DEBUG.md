# 디버그 런북

## 로컬 재현 절차

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
- `POST /mock/auth/issue-key` 성공/실패
- `POST /mock/process` timeout, 4xx, 5xx, 네트워크 오류
- job 상태 전이: `QUEUED -> PROCESSING -> RETRY_SCHEDULED|FAILED|SUCCEEDED`
- `leasedUntil` 기반 복구 처리

## 자주 발생하는 장애

- `WORKER_BASE_URL` 오설정으로 인한 연결 실패
- Worker `401`으로 인한 API Key 재발급 반복
- timeout 누적으로 인한 `MAX_ATTEMPTS_EXCEEDED`
- terminal job 만료 이후 `404 Not Found`

## 복구 절차

1. `WORKER_BASE_URL`, candidate identity, DB 연결 설정을 확인한다.
2. Worker 장애가 해소되면 scheduler가 `RETRY_SCHEDULED` 작업을 다시 집행하는지 확인한다.
3. `FAILED`로 종료된 job은 정책상 자동 복구되지 않으므로 재요청이 필요한지 판단한다.
4. stale `PROCESSING` job이 있으면 lease 만료 후 복구 로직이 `RETRY_SCHEDULED` 또는 `FAILED`로 정리하는지 확인한다.
