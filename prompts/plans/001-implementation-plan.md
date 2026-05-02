# 구현 계획

## 목표

- 이미지 작업 접수, 상태 조회, 결과 조회, 목록 조회를 지원하는 Spring Boot 백엔드의 핵심 코드를 구현한다.
- 이미 확정된 API 계약과 ADR을 기준으로 상태 전이, 중복 요청 처리, Worker 재시도, 재시작 복구, cleanup 정책을 코드에 반영한다.
- 테스트와 컨테이너 실행 기준을 유지하면서 이후 README, API 문서, 런북 마감 작업까지 이어질 수 있는 구현 골격을 완성한다.

## 제약

- 공개 API는 `imageUrl` 기반 JSON 요청만 허용하고 `Idempotency-Key` 헤더를 필수로 요구한다.
- 구현 순서는 `ImageInputType`, `ImageJob`, `JobStatus`, `JobStatusTransitionPolicy`, `ImageJobRepository`, `RequestHashService`, `ImageJobCommandService`, `ImageJobQueryService`, `ImageJobController`, `WorkerClient`, `RestWorkerClient`, `JobProcessor`, `JobProcessingScheduler`, `JobRecoveryService`, `JobCleanupScheduler`를 따른다.
- Worker API Key는 애플리케이션 startup 시 미리 발급하지 않고 최초 처리 시점에 lazy issuance 해야 한다.
- Worker 호출은 timeout 5초, 최대 3회 시도, `2초 -> 10초 -> 30초` backoff, `401` 재발급 재시도 정책을 따라야 한다.
- 작업 상태는 `QUEUED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`, `FAILED`만 사용하고 terminal state 이후 전이를 허용하지 않는다.
- 저장소와 비동기 실행은 DB-backed queue와 scheduler 기반으로 구현하고, 처리 보장 모델은 `at-least-once`를 유지해야 한다.
- terminal job 결과는 7일 보존하고 목록 조회는 `createdAt DESC`, `jobId DESC`, `page/size/status` 정책을 따른다.

## 단계

1. 도메인과 영속 모델을 구현한다.
2. `ImageInputType`, `JobStatus`, `ImageJob`, `JobStatusTransitionPolicy`, `ImageJobRepository`를 추가하고 상태 전이, lease, 결과 보존 필드를 엔티티에 반영한다.
3. 요청 처리와 조회 계층을 구현한다.
4. `RequestHashService`, `ImageJobCommandService`, `ImageJobQueryService`, `ImageJobController`를 추가하고 idempotency, 검증, 오류 응답, 목록 조회 정책을 API 계약에 맞춘다.
5. 외부 Worker 연동과 실행 파이프라인을 구현한다.
6. `WorkerClient`, `RestWorkerClient`, `JobProcessor`, `JobProcessingScheduler`, `JobRecoveryService`, `JobCleanupScheduler`를 추가하고 lazy API Key issuance, retry, recovery, cleanup 흐름을 완성한다.
7. 테스트와 설정을 보강한다.
8. H2/PostgreSQL 설정, scheduler 설정, Worker properties, 서비스 단위 테스트와 Spring 통합 테스트를 추가하고 `./gradlew test` 기준으로 검증한다.
9. 구현 결과를 문서에 반영한다.
10. README, API 문서, 런북에서 실제 코드와 다른 설명이 없는지 확인하고 필요 시 마지막 정합성 수정을 수행한다.

## 리스크와 대응

- 리스크: Worker 시작 요청과 상태 조회 사이에서 프로세스가 중단되면 내부 상태와 외부 처리 상태가 어긋날 수 있다.
- 대응: `PROCESSING` lease, 시도 횟수, stale recovery 규칙을 엔티티와 processor 경계에 명시적으로 반영한다.
- 리스크: idempotency, 상태 전이, scheduler 복구가 각각 다른 규칙으로 구현되면 데이터 정합성이 깨질 수 있다.
- 대응: command service와 transition policy를 단일 진입점으로 두고 상태 변경 로직을 분산시키지 않는다.
- 리스크: Worker 연동 구현이 실제 OpenAPI와 다르면 런타임 오류가 발생한다.
- 대응: `RestWorkerClient`는 확인된 Mock Worker 계약인 `issue-key`, `process`, `process/{job_id}` 경로와 `imageUrl` 요청 형식에만 맞춰 구현한다.
- 리스크: scheduler, cleanup, recovery 테스트가 약하면 재시작/만료 정책이 문서와 다르게 동작할 수 있다.
- 대응: 서비스 단위 테스트 외에 상태 전이, retry, recovery, cleanup 시나리오를 포함한 통합 테스트를 추가한다.
