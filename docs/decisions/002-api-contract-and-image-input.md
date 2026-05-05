# ADR 002: API 계약과 이미지 입력 형식

## 상태

채택

## 배경

이미지 데이터 표현 방식은 비동기 처리와 재시도에 적합해야 한다.
또한 Mock Worker의 실제 입력 계약은 `imageUrl` 필드 하나만 받는다.
따라서 공개 API가 Worker와 다른 입력 모델을 택하면 추가 저장소나 변환 계층이 필요해진다.

## 결정

- 작업 접수 API는 `POST /api/v1/image-jobs`로 정의한다.
- 작업 상태 조회 API는 `GET /api/v1/image-jobs/{jobId}`로 정의한다.
- 작업 결과 조회 API는 `GET /api/v1/image-jobs/{jobId}/result`로 정의한다.
- 작업 목록 조회 API는 `GET /api/v1/image-jobs`로 정의한다.
- 요청 형식은 `application/json`으로 제한한다.
- 작업 생성 요청은 `imageUrl` 단일 필드를 사용한다.
- `Idempotency-Key` 헤더를 공개 API 계약의 일부로 필수 요구한다.
- `multipart/form-data`는 과제 범위에서 제외한다.
- `imageUrl`은 `http`, `https` scheme만 허용한다.
- Worker 호출에는 같은 `imageUrl`을 전달한다.
- 상태 조회와 목록 조회 응답은 내부 job 상태를 그대로 노출한다.
- 결과 조회 응답은 terminal job에 대해서만 `result` 또는 `error`를 반환한다.
- `POST`는 새 작업이면 `202 Accepted`, 같은 요청 replay면 `200 OK`, 같은 key의 다른 요청이면 `409 Conflict`를 반환한다.
- 오류 응답은 `ApiErrorResponse(code, message)` 형식으로 통일하고, API 예외, validation error, unexpected exception 경계를 동일한 모델로 표현한다.

## 이유

- Worker의 실제 계약과 공개 API 계약을 일치시킬 수 있다.
- object storage나 파일 저장소를 추가하지 않아도 된다.
- API 계약이 단순하고 테스트하기 쉽다.
- URL 기반 요청은 worker 재시도와 재시작 복구 시 다시 전송하기 쉽다.
- `multipart/form-data`와 BASE64 입력 지원은 과제 범위를 넘어가므로 명시적으로 제외한다.

## 영향

- 장점:
  - 구현과 검증이 단순하다.
  - 재시도와 재시작 복구가 가능하다.
- 단점:
  - 클라이언트는 외부에서 접근 가능한 이미지 URL을 준비해야 한다.
  - 비공개 로컬 파일 업로드는 직접 지원하지 않는다.
- 향후 개선:
  - 실서비스에서는 object storage와 presigned URL 업로드를 추가한다.
