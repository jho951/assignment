# 2026-05-04 대화 요약

## 범위

- `docs/REQUIREMENTS.md` 해설과 실제 구현 목표 정리
- 당시 코드베이스 기준 TODO 우선순위 정리
- `MySQL` 사용 가능 여부와 평가 리스크 판단

## 핵심 결론

- 과제의 본질은 `Mock Worker`에 이미지를 위임하는 비동기 job 백엔드를 요구사항 contract를 지키며 완성하는 것이다.
- 구현 우선순위는 도메인/DB 모델, 공개 API, Worker client, 비동기 처리기, retry/recovery/cleanup scheduler, 테스트, Docker/문서 순서로 정리됐다.
- 당시 코드 상태는 핵심 골격이 이미 있었고, 남은 일은 `expiry 조회 semantics`, `interrupt/recovery 정리`, `운영 로그`, `핵심 시나리오 테스트`, `에러 응답 표준화`, `README/런북 정합성` 보강으로 요약됐다.
- `MySQL` 사용 자체가 과제 위반은 아니지만, 당시 저장소의 문서와 실행 환경이 PostgreSQL 전제였기 때문에 평가 리스크 최소화 관점에서는 PostgreSQL 유지가 더 안전하다고 판단했다.

## 후속 액션

1. 요구사항 보호 관점의 Kotlin 마이그레이션 체크리스트를 문서화한다.
2. Kotlin 마이그레이션은 `build/plugin -> DTO/properties/service -> controller/worker/processing -> entity/repository` 순으로 점진 적용한다.
3. 각 배치마다 `./gradlew test jacocoTestReport`와 문서 동기화를 함께 수행한다.
