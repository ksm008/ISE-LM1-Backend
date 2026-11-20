#!/bin/sh
# ISE-LM1 회원 DB (PostgreSQL) 컨테이너. 실습 서버에서 실행한다 (예: ~/iselm1-db/run-postgres.sh).
#
# - 접속 정보는 같은 폴더의 .env (권한 600). 없으면 비밀번호를 무작위로 만들어 생성한다.
# - 데이터는 Docker 볼륨 iselm1-pgdata 에 남는다 (컨테이너를 지워도 유지).
# - 5432 는 Tailscale(tailscale0)에서만 접속할 수 있게 iselm1-lab-firewall.sh 가 막는다.
# - 실습 컨테이너 정리 라벨(iselm1.lab)을 붙이지 않는다 (백엔드 시작 시 지워지지 않게).
set -eu

DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$DIR/.env"
NAME=iselm1-postgres
IMAGE="${PG_IMAGE:-postgres:17}"

if [ ! -f "$ENV_FILE" ]; then
    umask 077
    PASSWORD=$(openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-32)
    printf 'POSTGRES_DB=iselm1\nPOSTGRES_USER=iselm1\nPOSTGRES_PASSWORD=%s\n' "$PASSWORD" > "$ENV_FILE"
    echo "$ENV_FILE 을 만들었습니다 (비밀번호 무작위 생성)."
fi

sudo docker network inspect iselm1-db >/dev/null 2>&1 || sudo docker network create iselm1-db >/dev/null
sudo docker volume inspect iselm1-pgdata >/dev/null 2>&1 || sudo docker volume create iselm1-pgdata >/dev/null

if sudo docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
    echo "$NAME 컨테이너가 이미 있습니다. 다시 만들려면: sudo docker rm -f $NAME (데이터는 볼륨에 남음)"
    exit 0
fi

sudo docker run -d --name "$NAME" \
    --restart unless-stopped \
    --network iselm1-db \
    --env-file "$ENV_FILE" \
    -v iselm1-pgdata:/var/lib/postgresql/data \
    -p 5432:5432 \
    --memory 1g --shm-size 256m \
    "$IMAGE" >/dev/null

echo "$NAME 를 시작했습니다 ($IMAGE)."
