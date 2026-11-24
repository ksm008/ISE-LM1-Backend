#!/bin/bash
# GitHub main 에 새 커밋이 있으면 서버에서 테스트·빌드한 뒤 배포한다.
# systemd timer(iselm1-deploy.timer)가 1분마다 실행한다. 설치: deploy/README.md "자동 배포"
#
#   sudo iselm1-deploy.sh            새 커밋이 있는 저장소만 배포
#   sudo FORCE=1 iselm1-deploy.sh    새 커밋이 없어도 지금 main 을 다시 배포
#   sudo DRY_RUN=1 iselm1-deploy.sh  테스트·빌드만 하고 배포하지 않음
#   sudo MARK_ONLY=1 iselm1-deploy.sh 지금 main 을 "배포한 것"으로 기록만 함 (처음 설치할 때)
#
# 빌드는 전용 계정(iselm1-ci)으로 하고, 파일 교체·서비스 재시작만 root 로 한다.
# 같은 커밋은 실패해도 다시 시도하지 않는다 (다음 push 를 기다림). 상태: /var/lib/iselm1-deploy/
set -uo pipefail

CONFIG=/etc/iselm1-deploy.env
[ -r "$CONFIG" ] && . "$CONFIG"
SITE_URL=${SITE_URL:?SITE_URL 을 $CONFIG 에 지정}
BACKEND_REPO=${BACKEND_REPO:-https://github.com/ksm008/ISE-LM1-Backend.git}
FRONTEND_REPO=${FRONTEND_REPO:-https://github.com/ksm008/ISE-LM1-Frontend.git}
BRANCH=${BRANCH:-main}
CI_USER=iselm1-ci
CI_HOME=/opt/iselm1/ci
STATE=/var/lib/iselm1-deploy
APP_DIR=/opt/iselm1/backend
WEB_DIR=/var/www/iselm1
HEALTH_URL=http://127.0.0.1:8080/api/auth/config

exec 9>/run/iselm1-deploy.lock
flock -n 9 || exit 0
mkdir -p "$STATE"

log() { echo "[$1] ${*:2}" >&2; }   # stdout 은 checkout 의 결과(커밋 해시)로 쓰므로 로그는 stderr
# setpriv: runuser 와 달리 PAM 세션 기록(1분마다 auth 로그)이 남지 않는다
as_ci() { setpriv --reuid="$CI_USER" --regid="$CI_USER" --init-groups --reset-env -- env HOME="$CI_HOME" PATH=/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 "$@"; }  # LANG: 한글 테스트 이름(리포트 파일)

# 새 커밋이 있으면 작업 폴더를 그 커밋으로 맞추고 커밋 해시를 출력한다. 없으면 실패(1).
checkout() {
    local name=$1 repo=$2 dir=$CI_HOME/$1 commit last
    if [ ! -d "$dir/.git" ]; then
        as_ci git clone -q --branch "$BRANCH" "$repo" "$dir" || { log "$name" "clone 실패"; return 1; }
    fi
    as_ci git -C "$dir" fetch -q origin "$BRANCH" || { log "$name" "fetch 실패"; return 1; }
    commit=$(as_ci git -C "$dir" rev-parse "origin/$BRANCH") || return 1
    last=$(cat "$STATE/$name.last" 2>/dev/null || true)
    if [ "$commit" = "$last" ] && [ -z "${FORCE:-}" ]; then return 1; fi
    if [ -n "${MARK_ONLY:-}" ]; then
        echo "$commit" > "$STATE/$name.last"; log "$name" "기록만 함 ${commit:0:7}"; return 1
    fi
    as_ci git -C "$dir" reset -q --hard "$commit" && as_ci git -C "$dir" clean -qfdx -e node_modules || return 1
    echo "$commit"
}

finish() { # name commit 결과
    if [ -n "${DRY_RUN:-}" ]; then log "$1" "DRY_RUN: ${2:0:7} $3"; return; fi
    echo "$2" > "$STATE/$1.last"
    echo "$(date -Is) ${2:0:7} $3" >> "$STATE/$1.history"
    log "$1" "${2:0:7} $3"
}

deploy_backend() {
    local commit dir=$CI_HOME/backend jar
    commit=$(checkout backend "$BACKEND_REPO") || return 0
    log backend "새 커밋 ${commit:0:7}: 테스트·빌드"
    if ! as_ci bash -c "cd '$dir' && bash ./gradlew --no-daemon -q test bootJar"; then
        finish backend "$commit" "실패(테스트·빌드)"; return 1
    fi
    jar=$(ls "$dir"/build/libs/*.jar | grep -v -- '-plain.jar' | head -1)
    if [ -n "${DRY_RUN:-}" ]; then log backend "DRY_RUN: 빌드 성공 ($jar)"; return 0; fi

    cp -p "$APP_DIR/app.jar" "$APP_DIR/app.jar.prev" 2>/dev/null || true
    install -o iselm1 -g iselm1 -m 640 "$jar" "$APP_DIR/app.jar.new" && mv "$APP_DIR/app.jar.new" "$APP_DIR/app.jar"
    systemctl restart iselm1-backend
    if wait_healthy; then finish backend "$commit" "배포 완료"; return 0; fi

    log backend "새 버전이 응답하지 않아 이전 버전으로 되돌림"
    if [ -f "$APP_DIR/app.jar.prev" ]; then
        cp -p "$APP_DIR/app.jar.prev" "$APP_DIR/app.jar"; systemctl restart iselm1-backend; wait_healthy || true
    fi
    finish backend "$commit" "실패(기동 안 됨, 이전 버전 복구)"; return 1
}

wait_healthy() {
    for _ in $(seq 1 90); do
        curl -fsS -m 2 -o /dev/null "$HEALTH_URL" && return 0
        sleep 2
    done
    return 1
}

deploy_frontend() {
    local commit dir=$CI_HOME/frontend
    commit=$(checkout frontend "$FRONTEND_REPO") || return 0
    log frontend "새 커밋 ${commit:0:7}: 테스트·빌드"
    if ! as_ci bash -c "cd '$dir' && npm ci --no-audit --no-fund --loglevel=error && npm test --silent && VITE_API_BASE_URL='$SITE_URL' npm run build --silent"; then
        finish frontend "$commit" "실패(테스트·빌드)"; return 1
    fi
    if [ -n "${DRY_RUN:-}" ]; then log frontend "DRY_RUN: 빌드 성공"; return 0; fi

    rm -rf "$WEB_DIR.new" && cp -r "$dir/dist" "$WEB_DIR.new" && chown -R root:root "$WEB_DIR.new" && chmod -R a+rX "$WEB_DIR.new" || {
        finish frontend "$commit" "실패(파일 복사)"; return 1; }
    rm -rf "$WEB_DIR.prev"
    mv "$WEB_DIR" "$WEB_DIR.prev" && mv "$WEB_DIR.new" "$WEB_DIR"
    finish frontend "$commit" "배포 완료"
}

status=0
deploy_backend || status=1
deploy_frontend || status=1
exit $status
