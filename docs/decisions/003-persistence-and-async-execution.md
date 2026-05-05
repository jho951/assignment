# ADR 003: 작업 저장소와 비동기 실행 방식

## 상태

채택

## 배경

Mock Worker는 지연과 실패 가능성이 있는 외부 시스템이다.
작업 접수 API가 Worker 완료까지 블로킹되면 클라이언트 요청 시간이 길어지고 장애에 취약해진다.
또한 서버 재시작 후에도 작업 상태를 조회할 수 있어야 한다.

## 결정

- 작업 메타데이터와 결과는 RDBMS에 저장한다.
- 로컬/테스트 환경에서는 H2 file DB를 사용할 수 있다.
- 컨테이너 실행 환경에서는 PostgreSQL을 사용한다.
- 별도 메시지 큐는 도입하지 않고 DB-backed queue를 사용한다.
- Scheduler가 due `QUEUED`, `RETRY_SCHEDULED`, `PROCESSING` 작업을 조회한다.
- 처리 대상 작업은 transaction 안에서 `PROCESSING`으로 전환하거나 기존 `PROCESSING` continuation을 claim하고 `leaseUntil`을 설정한다.
- Worker 호출은 transaction 밖에서 수행한다.
- remote Worker가 계속 `PROCESSING`이면 lease를 해제하고 `nextAttemptAt` 기준으로 다음 scheduler tick에서 continuation을 재개한다.
- 오래된 `PROCESSING` 작업은 `leaseUntil` 기준으로 복구한다.

## 이유

- in-memory queue는 재시작 시 작업을 잃는다.
- 외부 메시지 큐는 과제 범위 대비 인프라가 과하다.
- DB-backed queue는 작업 이력, 상태 조회, 재시작 복구를 한 번에 만족한다.

## 영향

- 장점:
  - 재시작 후 작업 상태를 복구할 수 있다.
  - 추가 인프라 없이 컨테이너 환경에서 실행 가능하다.
- 단점:
  - 트래픽이 커지면 DB polling이 병목이 될 수 있다.
- 향후 개선:
  - 대규모 환경에서는 Kafka, RabbitMQ, SQS 같은 외부 큐로 분리한다.
  - 다중 인스턴스에서는 `FOR UPDATE SKIP LOCKED` 또는 분산 lock을 사용한다.
