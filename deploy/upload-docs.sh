#!/bin/bash
# 학습 문서(backend/docs, git 제외)를 배포 서버에 올린다. 개발 PC 의 Git Bash 에서 실행:
#   backend/deploy/upload-docs.sh
# 서버 주소는 application-local.properties 의 lab.docker.host 와 같은 호스트(Tailscale)를 쓴다.
# PuTTY(plink, pscp)와 SSH 키가 필요하다: ISELM1_SSH_KEY(기본 ~/.ssh/iselm1_lab.ppk), ISELM1_SSH_HOSTKEY(서버 호스트 키 지문)
set -euo pipefail
BACKEND=$(cd "$(dirname "$0")/.." && pwd)
HOST=${ISELM1_SSH_HOST:-$(grep -E '^lab\.docker\.host=' "$BACKEND/application-local.properties" | cut -d= -f2- | sed -E 's#^[a-z]+://##; s#:[0-9]+$##')}
KEY=${ISELM1_SSH_KEY:-$HOME/.ssh/iselm1_lab.ppk}
HOSTKEY=${ISELM1_SSH_HOSTKEY:-SHA256:V/o9GfEMtaWL9poTDEoYZA5ksfzxy5mSznvkLCuW9/s}
PLINK=$(command -v plink.exe || command -v plink || echo "/c/Program Files/PuTTY/plink.exe")
PSCP=$(command -v pscp.exe || command -v pscp || echo "/c/Program Files/PuTTY/pscp.exe")

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
tar --force-local -C "$BACKEND" --exclude='.obsidian' --exclude='.trash' -czf "$TMP/docs.tgz" docs
echo "올릴 문서: $(tar --force-local -tzf "$TMP/docs.tgz" | grep -c '\.md$')개"

"$PSCP" -batch -q -hostkey "$HOSTKEY" -i "$KEY" "$TMP/docs.tgz" "ubuntu@$HOST:/tmp/iselm1-docs.tgz"
"$PLINK" -batch -hostkey "$HOSTKEY" -i "$KEY" "ubuntu@$HOST" '
set -e
D=/opt/iselm1/backend
sudo rm -rf $D/docs.new && sudo mkdir $D/docs.new
sudo tar -C $D/docs.new --strip-components=1 -xzf /tmp/iselm1-docs.tgz && rm -f /tmp/iselm1-docs.tgz
sudo chown -R iselm1:iselm1 $D/docs.new
sudo rm -rf $D/docs.prev; if sudo test -d $D/docs; then sudo mv $D/docs $D/docs.prev; fi; sudo mv -T $D/docs.new $D/docs
echo "서버 반영 완료: $(sudo find $D/docs -name "*.md" | wc -l)개 (재시작 필요 없음)"'
