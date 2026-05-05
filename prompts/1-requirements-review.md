# 요구사항 검토 프롬프트

1. `docs/REQUIREMENTS.md`에서 기능 요구사항과 비기능 요구사항을 각각 요약합니다.
2. 설계가 필요한 항목을 `API`, `상태 모델`, `중복 요청`, `처리 보장`, `API Key 수명주기`, `Worker retry`, `재시작`, `운영성`으로 분류합니다.
3. `API` 항목에서는 공개 API가 외부 Mock Worker 실제 계약과 충돌하지 않는지 먼저 확인합니다.
4. `상태 모델` 항목에서는 허용 상태 전이와 비허용 상태 전이를 분리해 적습니다.
5. 각 항목에 대해 지금 결정할 것과 구현 중 검증할 것을 나눕니다.
6. `docs/REQUIREMENTS.md`의 미결정 항목이 현재 ADR 상태와 맞는지 확인합니다.
7. Worker가 꺼져 있어도 앱이 기동되어야 하는지, API Key를 startup 시 발급하지 말아야 하는지 확인합니다.
8. 컨테이너 실행 방식과 외부 서비스 주입 방식을 README에 어떻게 노출할지 정리합니다.
9. Mock Worker의 Swagger UI와 OpenAPI JSON을 기준 문서로 참조하는지 확인합니다.
10. `WORKER_BASE_URL`과 path 조합이 실제로 `.../mock/...` 최종 URL을 만드는지 확인합니다.
11. 우리 서버 공개 API 테스트용 Swagger를 노출할지, 노출한다면 Worker 문서와 어떻게 구분할지 확인합니다.
12. `docker/Dockerfile`, `docker/compose.yaml`, `.env.example`가 실제로 저장소에 있는지 확인합니다.
13. 공개 API 설계가 필요하면 코드 작성 전에 `docs/api/README.md` 초안을 먼저 만든다고 명시합니다.
14. 중요한 기술 선택만 ADR 후보로 올립니다.
