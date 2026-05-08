# ADR 인덱스

## 현재 활성 ADR

- `015-java-codebase-and-brokerage-order-integration.md`

## 이력 보존 ADR

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
- `012-processing-attempt-deadline-and-interrupt-recovery.md`
- `013-coverage-reporting-and-test-focus.md`
- `014-kotlin-migration-strategy-and-guardrails.md`

설명:

- 위 ADR들은 이전 이미지 작업/Kotlin 단계의 의사결정 기록으로 남겨둔다.
- 현재 코드베이스의 활성 기준선은 `015`이며, 새 변경은 이를 기준으로 이어간다.

## 연계 문서

- 구현 계획: `prompts/plans/001-implementation-plan.md`
- 공개 API 가이드: `docs/api/README.md`
- 디버그 런북: `docs/runbook/DEBUG.md`
- 대화 전문 로그: `docs/notes/conversation-log.md`
