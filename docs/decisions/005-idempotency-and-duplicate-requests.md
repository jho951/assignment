# ADR 005: 중복 요청 처리 정책

## 상태

채택

## 배경

클라이언트 재시도, 네트워크 오류, 중복 클릭 등으로 동일 요청이 여러 번 들어올 수 있다.
작업 접수 API가 매번 새 job을 생성하면 동일 이미지가 중복 처리될 수 있다.

## 결정

- 작업 접수 API는 `Idempotency-Key` 헤더를 필수로 요구한다.
- 동일 요청 판단 기준은 `(Idempotency-Key, requestHash)` 조합이다.
- 서버는 `idempotencyKey`와 `requestHash`를 저장한다.
- 같은 `Idempotency-Key`와 같은 `requestHash`가 들어오면 기존 job을 반환한다.
- 같은 `Idempotency-Key`지만 다른 `requestHash`가 들어오면 `409 Conflict`를 반환한다.
- 같은 payload라도 `Idempotency-Key`가 다르면 별도의 새 job으로 처리한다.
- `Idempotency-Key`가 없으면 `400 Bad Request`를 반환한다.
- DB에는 `idempotency_key` unique 제약을 둔다.

## 이유

- 클라이언트 재시도에 안전하다.
- 같은 key로 다른 요청을 보내는 실수를 감지할 수 있다.
- 중복 생성 방지를 DB 제약조건으로 보장할 수 있다.
- 인증 범위가 없는 현재 과제에서는 payload 전역 중복 제거보다 명시적 key 기반 idempotency가 동작을 더 예측 가능하게 만든다.

## 영향

- 장점:
  - 네트워크 재시도에도 job이 중복 생성되지 않는다.
  - 사용자 관찰 가능 동작이 명확하다.
- 단점:
  - 클라이언트는 Idempotency-Key를 생성해야 한다.
  - 같은 payload라도 다른 key면 중복 외부 처리가 발생할 수 있다.
- 향후 개선:
  - 인증이 추가되면 `clientId + idempotencyKey` 기준으로 scope를 좁힌다.
