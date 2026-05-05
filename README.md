# assignment

## 프로젝트 개요

이 과제는 동기식 이미지 처리 프록시가 아니라, 외부 Mock Worker를 대상으로 작업 수명주기를 관리하는 비동기 오케스트레이션 서버로 해석합니다.

클라이언트는 이미지 처리 요청을 서버에 제출하고, 서버는 작업을 `QUEUED` 상태로 저장한 뒤 즉시 `jobId`를 반환합니다. 실제 이미지 처리는 background scheduler/executor가 외부 Mock Worker에 위임하며, 클라이언트는 `jobId`를 사용해 작업 상태, 결과, 목록을 조회합니다.

```text
Client
  -> POST /api/v1/image-jobs
  -> Server stores job as QUEUED
  -> Scheduler processes job asynchronously
  -> Mock Worker processes image
  -> Client polls status/result/list APIs
```

---

## 빠른 시작

### 테스트 실행

```bash
./gradlew test
```

### 커버리지 리포트 생성

```bash
./gradlew test jacocoTestReport
```

리포트 경로:

- `build/reports/jacoco/test/html/index.html`
- `build/reports/jacoco/test/jacocoTestReport.xml`

현재 리포트 기준으로는 line coverage `94.9%`, branch coverage `80.5%`, method coverage `94.3%`까지 확인했습니다.

### 로컬 JVM 실행

```bash
./gradlew bootRun
```

### 컨테이너 실행

스크립트를 처음 실행하는 경우 권한을 부여합니다.

```bash
chmod +x scripts/*.sh
```

서비스 시작:

```bash
./scripts/run.sh
```

서비스 중지:

```bash
./scripts/stop.sh
```

컨테이너와 볼륨까지 초기화:

```bash
./scripts/stop.sh -v
```

내부적으로는 Docker Compose를 사용합니다.

```bash
docker compose -f docker/compose.yaml up --build -d
```

---

## 실행 방법

### 로컬 JVM 실행

- 기본 포트: `8080`
- 기본 로컬 DB: `jdbc:h2:file:./data/assignment`
- 실행 명령:

```bash
./gradlew bootRun
```

### 컨테이너 실행

- 시작:

```bash
./scripts/run.sh
```

- 중지:

```bash
./scripts/stop.sh
```

- 데이터까지 정리:

```bash
./scripts/stop.sh -v
```

- 서비스 포트:
  - 앱: `localhost:8080`
  - PostgreSQL: `localhost:5432`

- compose 기본 DB 접속 정보:
  - database: `assignment`
  - username: `assignment`
  - password: `assignment`

Mock Worker는 compose에 포함되지 않습니다. 외부 제공 서비스인 `https://dev.realteeth.ai/mock`을 사용하며, `.env.example`을 참고해 `.env`를 만들면 Worker 관련 환경 변수를 쉽게 덮어쓸 수 있습니다.

---

## API 개요

현재 API는 REST polling 방식입니다. 별도 SSE, WebSocket, Webhook 엔드포인트는 제공하지 않습니다.

| 목적 | Method | Path |
|---|---|---|
| 작업 생성 | `POST` | `/api/v1/image-jobs` |
| 작업 상태 조회 | `GET` | `/api/v1/image-jobs/{jobId}` |
| 작업 결과 조회 | `GET` | `/api/v1/image-jobs/{jobId}/result` |
| 작업 목록 조회 | `GET` | `/api/v1/image-jobs` |

### 작업 생성

```http
POST /api/v1/image-jobs
Content-Type: application/json
Idempotency-Key: request-001
```

```json
{
  "imageUrl": "https://example.com/image.jpg"
}
```

응답 예시:

```json
{
  "jobId": "job_abc123",
  "status": "QUEUED",
  "createdAt": "2026-05-04T06:00:00Z"
}
```

### 상태 조회

```http
GET /api/v1/image-jobs/{jobId}
```

상태는 다음 5개 중 하나입니다.

```text
QUEUED
PROCESSING
RETRY_SCHEDULED
SUCCEEDED
FAILED
```

### 결과 조회

```http
GET /api/v1/image-jobs/{jobId}/result
```

작업이 완료되면 결과를 조회합니다. 실패한 작업은 실패 코드와 메시지를 통해 원인을 확인합니다.

### 목록 조회

```http
GET /api/v1/image-jobs?page=0&size=20&status=SUCCEEDED
```

목록 조회는 `page`, `size`, `status` 필터를 지원합니다.

---

## 설계 요약

현재 채택한 핵심 정책은 다음과 같습니다.

### API 계약

- 작업 접수 API는 `POST /api/v1/image-jobs`를 사용합니다.
- 작업 상태 조회, 결과 조회, 목록 조회 API는 각각 `GET /api/v1/image-jobs/{jobId}`, `GET /api/v1/image-jobs/{jobId}/result`, `GET /api/v1/image-jobs`를 사용합니다.
- 요청 형식은 `application/json`이며 작업 생성 요청은 `imageUrl` 단일 필드를 사용합니다.
- `imageUrl`은 `http`, `https`만 허용합니다.
- `multipart/form-data`와 BASE64 업로드는 제외합니다.
- 서버는 scheme이 누락된 URL을 자동 보정하지 않고 validation error로 처리합니다.

### 저장소와 실행 방식

- 작업 메타데이터와 결과는 RDBMS에 저장합니다.
- 별도 메시지 큐 없이 DB-backed queue와 scheduler 기반 비동기 실행을 사용합니다.
- 로컬 JVM 실행과 테스트는 H2 file DB를 사용합니다.
- 컨테이너 실행 환경은 PostgreSQL을 기준으로 구성합니다.

### 상태 모델

- 작업 상태는 `QUEUED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`, `FAILED` 5개로 정의합니다.
- `SUCCEEDED`, `FAILED`는 terminal state입니다.
- 재시도 가능한 실패와 executor interruption 은 `RETRY_SCHEDULED`로 이동할 수 있고, 재시도 불가능하거나 재시도 상한을 초과한 실패는 `FAILED`로 종료합니다.
- `attemptCount`는 새 Worker start 호출 수가 아니라 scheduler가 job을 claim해 처리 또는 기존 remote job polling 재개를 시도한 횟수입니다.

### 중복 요청 처리

- 작업 접수에는 `Idempotency-Key` 헤더를 필수로 요구합니다.
- `Idempotency-Key`는 trim 후 `1..128`자이며 영문 대소문자, 숫자, `.`, `_`, `-`만 허용합니다.
- 동일 요청 판단 기준은 `(Idempotency-Key, requestHash)`입니다.
- 유효한 새 요청이면 새 job을 만들고 `QUEUED`를 반환한 뒤 비동기 처리로 넘깁니다.
- 같은 key와 같은 요청이면 기존 job을 반환하고, 이때 응답 `status`는 기존 job의 현재 상태를 그대로 사용합니다.
- 같은 key에 다른 요청이면 `409 Conflict`를 반환합니다.
- 같은 `imageUrl`이라도 key가 다르면 별도 새 job으로 처리합니다.

### 처리 보장과 복구

- 본 시스템의 처리 보장 모델은 `at-least-once`입니다.
- 외부 Worker 호출은 장애 경계에서 중복 실행될 수 있으므로, lease 기반 복구와 제한된 재시도 정책으로 불일치를 완화합니다.
- stale `PROCESSING` 작업은 `leaseUntil` 기준으로 복구합니다.
- executor thread는 한 번 claim한 job에 대해 `startProcess` 또는 `getProcessStatus` 한 번만 수행합니다.
- remote job이 아직 `PROCESSING`이면 local status는 그대로 `PROCESSING`을 유지하고 lease를 해제한 뒤 `nextAttemptAt = now + pollInterval`로 다음 scheduler tick에 재개합니다.

### 외부 Worker 연동

- Mock Worker API Key는 코드에 하드코딩하지 않고 설정값과 런타임 메모리 캐시로만 관리합니다.
- 애플리케이션 startup 시 Worker API Key를 미리 발급하지 않고, 최초 job 처리 시 lazy하게 발급합니다.
- 기본 API Key 발급 path는 `/auth/issue-key`이며 기본 최종 URL은 `https://dev.realteeth.ai/mock/auth/issue-key`입니다.
- 최초 호출 시 key를 발급하고, `401` 응답 시 key를 폐기한 뒤 1회 재발급 후 즉시 재시도합니다.
- timeout은 5초, 일반 재시도 상한은 3회입니다.
- Worker 장애는 job 상태와 오류 코드로 표현합니다.

### 결과 보존과 목록 조회

- terminal job 결과는 완료 시각 기준 7일 보존합니다.
- `expiresAt`이 지난 terminal job은 cleanup scheduler가 아직 물리 삭제하지 않았더라도 상태 조회/결과 조회에서 즉시 `404`, 목록 조회에서는 즉시 제외합니다.
- 목록 조회 API는 `GET /api/v1/image-jobs`입니다.
- 정렬은 `createdAt DESC`, `jobId DESC`를 사용합니다.
- `page`, `size`, `status` 필터를 지원합니다.

---

## 추가 요구사항 대응

### 4.1 중복 요청 처리

동일한 요청이 네트워크 재시도, 중복 클릭, 클라이언트 재전송으로 여러 번 전달될 수 있다고 가정합니다. 이를 위해 작업 생성 API는 `Idempotency-Key` 헤더를 필수로 요구합니다.

서버는 `Idempotency-Key`와 요청 본문에서 계산한 `requestHash`를 함께 저장합니다.

- 같은 `Idempotency-Key`와 같은 `requestHash`가 들어오면 기존 job을 반환합니다.
- 같은 `Idempotency-Key`지만 다른 `requestHash`가 들어오면 `409 Conflict`를 반환합니다.
- 같은 imageUrl이라도 다른 `Idempotency-Key`를 사용하면 별도의 새 job으로 처리합니다.

동일 key 요청이 동시에 들어오는 경우에는 DB unique constraint로 단일 job만 생성되도록 보장합니다. 동시 insert 충돌이 발생하면 기존 job을 다시 조회하고, requestHash가 같으면 replay로 반환합니다.

### 4.2 상태 전이

작업 상태는 다음 5개로 정의합니다.

- `QUEUED`: 작업 접수 완료, Worker 호출 전
- `PROCESSING`: remote Worker 작업이 진행 중이거나, 그 상태를 scheduler가 추적 중인 상태
- `RETRY_SCHEDULED`: 일시적 실패 또는 interruption 이후 backoff를 두고 재시도 대기
- `SUCCEEDED`: 최종 성공
- `FAILED`: 최종 실패

허용 상태 전이는 다음과 같습니다.

- `QUEUED -> PROCESSING`
- `PROCESSING -> SUCCEEDED`
- `PROCESSING -> RETRY_SCHEDULED`
- `PROCESSING -> FAILED`
- `RETRY_SCHEDULED -> PROCESSING`
- `RETRY_SCHEDULED -> FAILED`

`SUCCEEDED`, `FAILED`는 terminal state입니다. terminal state에 도달한 작업은 다시 처리 상태로 돌아가지 않습니다.

명시되지 않은 상태 전이는 허용하지 않습니다. 예를 들어 `QUEUED -> SUCCEEDED`, `SUCCEEDED -> PROCESSING`, `FAILED -> SUCCEEDED`는 허용하지 않습니다.

### 4.3 처리 보장 모델

본 시스템은 `at-least-once` 처리 보장 모델을 따릅니다.

작업 생성 요청은 `Idempotency-Key`와 `requestHash`를 기준으로 중복 생성을 방지합니다. 따라서 같은 key와 같은 body로 요청이 반복되면 새 job을 만들지 않고 기존 job을 반환합니다.

하지만 Mock Worker는 외부 시스템이며, 우리 서버의 DB 트랜잭션과 Mock Worker 호출을 하나의 원자적 트랜잭션으로 묶을 수 없습니다. 따라서 Worker 호출이 성공한 직후 서버가 중단되거나, Worker 완료 결과를 DB에 저장하기 전에 장애가 발생하면 같은 내부 job이 다시 처리될 수 있습니다.

이러한 이유로 Worker 호출의 `exactly-once`는 보장하지 않습니다. 대신 job 상태를 RDBMS에 저장하고, `leaseUntil` 기반 stale job 복구, 재시도 상한, terminal state 고정을 통해 작업이 유실되지 않도록 설계했습니다.

### 4.4 서버 재시작 시 동작

작업 상태와 결과는 RDBMS에 저장하므로 서버가 재시작되어도 job 메타데이터는 유지됩니다. 반면 실행 중이던 background thread와 Mock Worker API Key 메모리 캐시는 사라집니다.

서버가 다시 기동되면 scheduler가 재개되고, `PROCESSING` 상태이면서 `leaseUntil`이 현재 시각보다 지난 작업을 stale job으로 판단합니다. 해당 작업의 시도 횟수가 남아 있으면 `RETRY_SCHEDULED`로 전환해 다시 처리하고, 최대 시도 횟수를 초과하면 `FAILED`로 종료합니다.

`QUEUED` 작업은 그대로 대기 상태로 남아 있다가 scheduler에 의해 처리됩니다. `RETRY_SCHEDULED` 작업은 `nextAttemptAt` 이후 다시 처리됩니다. `PROCESSING` 상태이지만 lease가 해제된 작업은 `nextAttemptAt` 이후 기존 remote job polling을 한 단계씩 재개합니다. `SUCCEEDED`, `FAILED`는 terminal state이므로 재시작 후에도 다시 처리하지 않습니다.

### 데이터 정합성 경계

DB 내부의 상태 변경은 트랜잭션으로 처리합니다. 예를 들어 job 생성, 상태 전이, externalJobId 저장, 결과 저장은 각각 원자적으로 반영합니다.

다만 외부 Mock Worker 호출과 로컬 DB 업데이트는 하나의 원자적 트랜잭션으로 묶을 수 없습니다. Mock Worker는 외부 시스템이며 DB 트랜잭션의 rollback 대상이 아니기 때문입니다.

따라서 다음 경계에서는 중복 처리 또는 상태 반영 지연이 발생할 수 있습니다.

1. Mock Worker 작업 생성은 성공했지만 `externalJobId`를 DB에 저장하기 전에 서버가 중단되는 경우
2. Mock Worker 완료 결과를 받았지만 `SUCCEEDED`와 result를 DB에 저장하기 전에 서버가 중단되는 경우
3. 우리 서버에서는 timeout으로 판단했지만 Mock Worker에서는 작업이 접수되었거나 계속 처리 중인 경우

이러한 이유로 Worker 호출의 `exactly-once`는 보장하지 않습니다. 현재 구조는 `at-least-once` 처리 모델을 따르며, DB transaction, lease 기반 복구, 재시도 상한, terminal state 고정으로 불일치를 완화합니다.

---

## 외부 시스템 연동 방식 및 선택 이유

### PostgreSQL을 선택한 이유

이 시스템에서 데이터베이스는 단순히 작업 결과를 저장하는 용도가 아니라, 비동기 작업의 상태와 생명주기를 관리하는 기준점 역할을 합니다.

작업 생성, 중복 요청 방지, 상태 전이, 재시도 횟수, lease 기반 복구, 결과 보존을 안정적으로 관리해야 하므로 트랜잭션, unique constraint, 인덱스, row-level locking을 안정적으로 지원하는 RDBMS가 필요하다고 판단했습니다.

PostgreSQL은 이러한 요구사항을 충족하며, Docker Compose로 평가자 로컬 환경에서도 별도 상용 계정 없이 재현 가능하게 실행할 수 있습니다.

### DB-backed queue를 선택한 이유

이 과제의 핵심은 이미지 처리 요청을 즉시 완료하는 것이 아니라, 작업을 접수한 뒤 비동기로 처리 상태를 관리하는 것입니다.

작업 상태, 재시도 횟수, 실패 원인, 결과, 서버 재시작 후 복구 정보를 저장해야 하므로 별도 인메모리 큐보다 RDBMS 기반 job table을 작업 큐처럼 사용하는 방식을 선택했습니다.

DB-backed queue를 사용하면 다음 요구사항을 단순한 구조로 만족할 수 있습니다.

- 작업 상태 조회
- 작업 목록 조회
- 중복 요청 방지
- 재시도 이력 관리
- 서버 재시작 후 진행 중 작업 복구

즉, DB는 단순 저장소가 아니라 작업의 상태와 생명주기를 관리하는 기준점 역할을 합니다.

### Kafka/RabbitMQ를 사용하지 않은 이유

Kafka나 RabbitMQ 같은 외부 메시지 큐를 사용하면 대량 트래픽 처리와 소비자 확장에는 유리합니다.

하지만 이 과제에서는 평가자가 로컬 환경에서 별도의 상용 계정 없이 실행할 수 있어야 하고, 추가 인프라는 바로 실행 가능해야 합니다. 또한 현재 범위에서는 메시지 브로커 운영보다 작업 상태 모델, 중복 요청 처리, 재시도, 재시작 복구를 명확히 설계하는 것이 더 중요하다고 판단했습니다.

따라서 초기 구현에서는 Kafka/RabbitMQ를 도입하지 않고 RDBMS 기반 queue로 단순화했습니다.

향후 트래픽이 증가해 DB polling이 병목이 되거나 worker consumer를 수평 확장해야 하는 경우에는 RabbitMQ, Kafka, SQS 같은 외부 queue 도입을 검토할 수 있습니다.

### API Key를 캐싱하는 이유

Mock Worker는 `/mock/auth/issue-key` API를 통해 발급받은 API Key를 이후 요청의 `X-API-KEY` 헤더에 포함하도록 요구합니다.

이미지 처리 요청마다 API Key를 새로 발급하면 인증 API 호출이 불필요하게 증가하고, 순간 트래픽 상황에서 인증 API가 병목이 될 수 있습니다. 또한 API Key 발급 실패가 모든 이미지 처리 요청 실패로 직접 이어질 수 있습니다.

따라서 애플리케이션은 최초 Worker 호출 시 API Key를 lazy하게 발급받고 메모리에 캐싱합니다. 이후 Worker 요청에는 캐시된 key를 재사용합니다.

서버 재시작으로 캐시가 사라지거나 Worker가 인증 실패 응답을 반환하면 기존 key를 폐기하고 다시 발급받습니다.

### polling 방식을 선택한 이유

Mock Worker의 처리 시간은 수 초에서 수십 초까지 달라질 수 있고, 요청 시점에 즉시 완료되지 않습니다. 따라서 우리 서버가 Worker 처리 요청을 보낸 뒤 즉시 최종 결과를 반환하는 방식은 적절하지 않습니다.

또한 Mock Worker가 callback/webhook을 제공한다는 요구사항이 없으므로, 서버가 Worker job 상태를 주기적으로 조회하는 polling 방식을 선택했습니다.

polling 방식은 구조가 단순하고, 서버가 작업 상태를 일관되게 관리할 수 있다는 장점이 있습니다. 다만 작업 수가 많아지면 polling 요청 수와 DB 조회가 증가할 수 있으므로, polling interval, batch size, executor thread 수를 설정값으로 관리합니다.

향후 Worker가 webhook을 제공하거나 작업량이 크게 증가하면 callback 기반 처리나 외부 queue 기반 consumer 구조로 확장할 수 있습니다.

---

## 트래픽 증가 시 병목 가능 지점

현재 구조는 단일 애플리케이션 인스턴스와 DB-backed queue를 기준으로 설계되어 있습니다. 트래픽이 증가하면 다음 지점이 병목이 될 수 있습니다.

- DB polling: scheduler가 처리 대상 job을 주기적으로 조회하는 과정
- executor thread pool: 동시에 처리 가능한 Worker 작업 수 제한
- Mock Worker latency: 외부 Worker의 처리 지연이 전체 완료 시간에 직접 영향
- DB write 부하: 상태 변경, retry 기록, result 저장이 증가하는 경우
- result payload 저장: 결과 데이터가 커질 경우 DB 저장소와 조회 비용 증가

현재 구현은 polling interval, batch size, executor thread 수를 설정값으로 분리해 부하를 조정할 수 있게 했습니다.

향후 작업량이 증가하면 다음 개선을 검토할 수 있습니다.

- `nextAttemptAt`을 workload 특성에 따라 더 세분화하고 adaptive poll interval을 도입
- Kafka, RabbitMQ, SQS 같은 외부 queue 도입
- worker consumer 분리 및 수평 확장
- result payload를 object storage로 분리
- cursor 기반 pagination 도입

---

## 런타임 구성

- 애플리케이션 이미지는 `docker/Dockerfile`로 빌드합니다.
- 로컬 JVM 실행은 H2 file DB를 사용합니다.
- 컨테이너 실행은 `docker/compose.yaml` 기준 `app + PostgreSQL`입니다.
- Mock Worker는 저장소에 포함되지 않은 외부 제공 서비스로 가정합니다.
- Mock Worker 주소는 `WORKER_BASE_URL` 환경 변수로 주입합니다.
- Worker가 꺼져 있어도 애플리케이션은 기동되어야 하며, 실제 job 처리 시점에만 Worker 연결이 필요합니다.
- 기본 Worker base URL은 `https://dev.realteeth.ai/mock`입니다.

---

## 주요 환경 변수

| 변수                           | 설명                         |
|------------------------------|----------------------------|
| `WORKER_BASE_URL`            | 외부 Mock Worker base URL    |
| `WORKER_ISSUE_KEY_PATH`      | Worker API Key 발급 상대 path |
| `WORKER_CANDIDATE_NAME`      | API Key 발급용 candidate name |
| `WORKER_CANDIDATE_EMAIL`     | API Key 발급용 email          |
| `SPRING_DATASOURCE_URL`      | PostgreSQL 또는 로컬 DB 연결 주소  |
| `SPRING_DATASOURCE_USERNAME` | DB 계정                      |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호                    |

---

## Mock Worker 참조 문서

- Base URL: `https://dev.realteeth.ai/mock`
- Swagger UI: `https://dev.realteeth.ai/mock/docs`
- OpenAPI JSON: `https://dev.realteeth.ai/mock/openapi.json`

---

## Swagger

- 공개 API 테스트용 OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- 공개 API 테스트용 Swagger UI: `http://localhost:8080/swagger-ui.html`
- Worker 스펙 확인은 `https://dev.realteeth.ai/mock/docs`를 사용합니다.

---

## 검증 범위

- `./gradlew test`로 자동화 테스트를 실행합니다.
- coverage report가 필요하면 `./gradlew test jacocoTestReport`를 실행합니다.
- 실제 Mock Worker live 호출은 외부 서비스 상태와 네트워크에 영향을 받습니다.
- Worker가 꺼져 있어도 앱 기동과 공개 API 테스트는 가능합니다.

---

## 협업 산출물

- 요구사항: `docs/REQUIREMENTS.md`
- 의사결정 기록: `docs/decisions/`
- 주요 ADR:
  - `001-async-job-orchestration.md`
  - `002-api-contract-and-image-input.md`
  - `003-persistence-and-async-execution.md`
  - `004-job-state-and-retry-policy.md`
  - `005-idempotency-and-duplicate-requests.md`
  - `006-result-retention-and-list-policy.md`
  - `007-processing-guarantee-and-api-key-lifecycle.md`
  - `008-worker-integration-and-retry.md`
  - `009-container-and-runtime-config.md`
  - `010-openapi-and-swagger-ui.md`
  - `011-lombok-for-boilerplate-reduction.md`
- 프롬프트: `prompts/`
- 실행/장애 가이드: `docs/runbook/DEBUG.md`
- API 문서 기준: `docs/api/README.md`
- 호환성 정책: `docs/compat/README.md`
