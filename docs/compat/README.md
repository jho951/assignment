# 호환성 정책

## 런타임 기준

- Java: `17`
- Spring Boot: `4.0.6`
- Gradle Wrapper: `9.4.1`
- PostgreSQL: `16.x` 이상을 기준으로 검증한다.
- Docker Compose: compose v2 계열을 기준으로 문서화한다.

## API 호환성

- 공개 API base path는 `/api/v1/stock-orders`로 고정한다.
- 상태 이름 `QUEUED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`, `FAILED`는 브레이킹 체인지 없이 유지한다.
- `Idempotency-Key` 헤더 요구사항은 호환성 계약의 일부로 본다.
- read API 의 `accountNumberMasked` 필드는 마스킹 정책을 유지한다.
- 오류 코드 문자열은 클라이언트 계약의 일부로 보고 임의 변경하지 않는다.

## 외부 증권사 연동 호환성

- 기본 증권사 연동 path는 `/oauth2/token`, `/v1/orders`, `/v1/orders/{orderId}`다.
- access token 은 lazy issuance 와 `401` refresh retry 정책을 유지한다.
- 우리 서버의 Swagger는 공개 API 테스트용이며, 증권사 raw API 스펙의 대체 문서가 아니다.

## 마이그레이션 원칙

- 공개 API의 경로, 응답 필드, 상태 이름을 바꿔야 하면 새 버전 경로를 추가하는 방식을 우선 검토한다.
- DB 스키마 변경은 기존 job 조회와 복구 흐름을 깨지 않도록 점진적 변경을 우선한다.
- 증권사 path, 인증 방식, masking 정책이 바뀌면 ADR과 README, API 문서, 런북을 동시에 갱신한다.
