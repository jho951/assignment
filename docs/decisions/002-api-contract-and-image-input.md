# ADR 002: API 계약과 이미지 입력 형식

## 상태

채택

## 배경

이미지 데이터 표현 방식 비동기로 처리되므로, 서버는 Worker 호출 재시도에 필요한 입력 데이터를 보존할 수 있어야 한다.

## 결정

- 작업 접수 API는 `POST /api/v1/image-jobs`로 정의한다.
- 요청 형식은 `application/json`으로 제한한다.
- 이미지 입력은 `image.type`과 `image.value`로 표현한다.
- `image.type`은 `URL` 또는 `BASE64`만 허용한다.
- `multipart/form-data`는 과제 범위에서 제외한다.
- URL은 `http`, `https` scheme만 허용한다.
- BASE64 이미지는 decoded size 기준 최대 5MB로 제한한다.
- Worker 호출에도 동일한 이미지 표현 구조를 전달한다.
- `image.value`는 로그에 출력하지 않는다.

## 이유

- 비동기 재시도에 필요한 입력 데이터를 저장할 수 있다.
- object storage나 파일 저장소를 추가하지 않아도 된다.
- API 계약이 단순하고 테스트하기 쉽다.
- 대용량 이미지 처리와 `multipart/form-data`는 과제 범위를 넘어가므로 명시적으로 제한한다.

## 영향

- 장점:
  - 구현과 검증이 단순하다.
  - 재시도와 재시작 복구가 가능하다.
- 단점:
  - BASE64는 네트워크와 저장소 사용량이 증가한다.
  - 대용량 이미지 처리에는 적합하지 않다.
- 향후 개선:
  - 실서비스에서는 object storage와 presigned URL 방식을 사용한다.

