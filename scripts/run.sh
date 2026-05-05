#!/bin/bash

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 1. 경로 설정
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ROOT_DIR="$( dirname "$SCRIPT_DIR" )"
COMPOSE_PATH="docker/compose.yaml"

cd "$ROOT_DIR"

echo -e "${YELLOW}🔍 시스템 상태 점검 중...${NC}"

# 2. 도커 데몬 확인
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}❌ 에러: 도커(Docker)가 실행 중이지 않습니다.${NC}"
    exit 1
fi

# 3. 설정 파일 확인
if [ ! -f "$COMPOSE_PATH" ]; then
    echo -e "${RED}❌ 에러: 설정 파일을 찾을 수 없습니다. ($COMPOSE_PATH)${NC}"
    exit 1
fi

echo -e "${GREEN}🚀 서비스 빌드 및 시작 중...${NC}"

# 4. Docker Compose 실행
if docker compose -f "$COMPOSE_PATH" up --build -d; then
    echo "------------------------------------------------"
    echo -e "${GREEN}✅ 가동 성공!${NC}"
    echo -e "🏠 앱: http://localhost:8080"
    echo -e "🗄️ DB: localhost:5432"
    echo "------------------------------------------------"
    echo -e "${YELLOW}💡 로그 확인:${NC} docker compose -f $COMPOSE_PATH logs -f"
else
    echo -e "${RED}❌ 에러 발생!${NC}"
    exit 1
fi
