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

echo -e "${YELLOW}🛑 서비스를 중지하는 중...${NC}"

# 2. Docker Compose 중지
# -v 옵션 처리 (인자로 -v를 넘기면 데이터까지 삭제)
if [ "$1" == "-v" ]; then
    echo -e "${RED}⚠️  주의: 데이터 볼륨까지 모두 삭제합니다.${NC}"
    docker compose -f "$COMPOSE_PATH" down -v
else
    docker compose -f "$COMPOSE_PATH" down
fi

# 3. 결과 확인
if [ $? -eq 0 ]; then
    echo "------------------------------------------------"
    echo -e "${GREEN}✅ 서비스가 안전하게 중지되었습니다.${NC}"
    echo "------------------------------------------------"
else
    echo -e "${RED}❌ 중지 중 에러가 발생했습니다.${NC}"
    exit 1
fi
