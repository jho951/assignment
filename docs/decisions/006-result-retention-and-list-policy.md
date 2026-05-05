# ADR 006: 결과 보존 기간과 목록 조회 정책

## 상태

채택

## 배경

클라이언트는 완료된 작업의 결과와 작업 목록을 조회할 수 있어야 한다.
동시에 작업 결과를 무기한 보존하면 저장소 사용량이 계속 증가한다.
따라서 결과 보존 기간과 목록 조회 정책을 명확히 정의해야 한다.

## 결정

- `SUCCEEDED`, `FAILED` 상태의 terminal job은 완료 시각 기준 7일간 보존한다.
- terminal job에는 `expiresAt = completedAt + 7 days`를 설정한다.
- `expiresAt`이 지난 terminal job은 cleanup 대상이다.
- `expiresAt`이 지난 terminal job은 cleanup 이전이라도 read path에서 즉시 숨긴다.
- 상태 조회와 결과 조회는 `404 Not Found`, 목록 조회는 제외된 것으로 처리한다.
- 삭제된 job 조회는 `404 Not Found`를 반환한다.
- 목록 조회 API는 `GET /api/v1/image-jobs`로 정의한다.
- 목록은 `createdAt DESC`, `jobId DESC` 순서로 정렬한다.
- pagination은 `page`, `size` query parameter로 제공한다.
- 기본 `page`는 0, 기본 `size`는 20, 최대 `size`는 100이다.
- `status` filter를 지원한다.

## 이유

- 7일 보존은 평가와 디버깅에 충분하다.
- 무기한 보존보다 저장소 증가 위험을 줄일 수 있다.
- cleanup scheduler 주기와 무관하게 사용자 관찰 가능 계약을 일정하게 유지할 수 있다.
- pagination과 status filter는 작업 수 증가에 대비한 최소한의 보호 장치다.

## 영향

- 장점:
  - 목록 API가 대량 데이터 상황에서도 안전하다.
  - 결과 보존 정책이 명확하다.
- 단점:
  - 7일 이후에는 같은 jobId로 결과를 조회할 수 없다.
  - cleanup 완료 전까지 DB row는 남아 있을 수 있으므로 물리 삭제 시점과 API 가시성 시점이 분리된다.
- 향후 개선:
  - 대규모 환경에서는 결과 payload를 object storage로 분리한다.
  - cursor-based pagination을 도입한다.
