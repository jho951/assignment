# ADR 010: 우리 서버 Swagger 노출과 Mock Worker OpenAPI 기준 사용

## 상태

채택

## 배경

Mock Worker는 외부에서 제공되는 서비스이며, 실제 연동 스펙은 과제 참가자가 임의로 재정의하면 안 된다.
동시에 평가나 수동 검증에서는 우리 서버 공개 API를 브라우저에서 직접 호출해 볼 수 있는 진입점이 있으면 편하다.
따라서 Worker 문서는 외부 제공 OpenAPI를 기준으로 유지하고, 우리 서버는 별도 Swagger를 공개 API 테스트용으로만 노출한다.

## 결정

- Worker base URL 기본값은 `https://dev.realteeth.ai/mock`를 사용한다.
- Worker 참조 문서는 아래 외부 제공 문서를 기준으로 사용한다.
  - Swagger UI: `https://dev.realteeth.ai/mock/docs`
  - OpenAPI JSON: `https://dev.realteeth.ai/mock/openapi.json`
- 애플리케이션은 SpringDoc을 사용해 우리 서버 공개 API용 OpenAPI 문서와 Swagger UI를 노출한다.
- 우리 서버 공개 API OpenAPI JSON 경로는 `/v3/api-docs`를 사용한다.
- 우리 서버 공개 API Swagger UI 경로는 `/swagger-ui.html`을 사용한다.
- 우리 서버 Swagger에는 `api/v1/image-jobs` 계열 공개 API만 노출한다.
- Mock Worker API는 우리 서버 Swagger에 포함하지 않고, 외부 제공 문서를 참조한다.
- Worker 경로 기본값은 base URL 기준 상대 경로로 관리한다.
  - API Key 발급: `/auth/issue-key`
  - 처리 시작 및 상태 조회: `/process`, `/process/{jobId}`

## 이유

- 공개 API 수동 테스트 경로를 제공하면 검증과 데모가 쉬워진다.
- 외부에서 제공한 OpenAPI를 기준으로 삼으면 Worker 계약 드리프트를 줄일 수 있다.
- Worker 문서와 우리 서버 문서를 분리하면 역할이 명확해진다.

## 영향

- 장점:
  - 우리 서버 공개 API를 브라우저에서 바로 테스트할 수 있다.
  - Worker 연동 대상이 명확해진다.
  - `WORKER_BASE_URL` 기본값만으로 외부 Mock Worker와 바로 맞출 수 있다.
- 단점:
  - SpringDoc 의존성과 Swagger 노출 구성이 추가된다.
- 향후 개선:
  - 필요하면 운영 profile에서 Swagger 비활성화 옵션을 추가할 수 있다.
