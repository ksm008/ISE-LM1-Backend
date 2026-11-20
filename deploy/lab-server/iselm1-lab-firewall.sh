#!/bin/sh
# ISE-LM1 실습 컨테이너 방화벽 (실습 서버 호스트에서 root 로 실행)
#
# 실습 컨테이너(기본 브리지 docker0)에서 나가는 통신 중 아래만 막는다. 인터넷(nmap, ping, curl 등)은 그대로 쓸 수 있다.
#   - 클라우드 메타데이터 서비스 169.254.0.0/16 (DNS 53번만 허용)
#   - 실습 서버 호스트 자신 (SSH, Docker API, eBPF 에이전트 등)
#   - 다른 실습 컨테이너 (ISOLATE_CONTAINERS=1)
#   - VCN 등 사설망 (BLOCK_PRIVATE=1)
#
# 규칙은 전용 체인(ISELM1-LAB-FWD, ISELM1-LAB-HOST)에 넣고, 실행할 때마다 비우고 다시 채운다(여러 번 실행해도 같다).
#   sudo ./iselm1-lab-firewall.sh         규칙 적용
#   sudo ./iselm1-lab-firewall.sh stop    규칙 제거
set -eu

BRIDGE="${BRIDGE:-docker0}"
DNS_SERVER="${DNS_SERVER:-169.254.169.254}"
ISOLATE_CONTAINERS="${ISOLATE_CONTAINERS:-1}"
BLOCK_PRIVATE="${BLOCK_PRIVATE:-1}"

ipt() { iptables -w "$@"; }
reject() { ipt -A "$@" -j REJECT --reject-with icmp-admin-prohibited; }

remove_rules() {
    while ipt -t mangle -C PREROUTING -i "$BRIDGE" -j ISELM1-LAB-PRE 2>/dev/null; do
        ipt -t mangle -D PREROUTING -i "$BRIDGE" -j ISELM1-LAB-PRE
    done
    ipt -t mangle -F ISELM1-LAB-PRE 2>/dev/null || true
    ipt -t mangle -X ISELM1-LAB-PRE 2>/dev/null || true
    while ipt -C DOCKER-USER -i "$BRIDGE" -j ISELM1-LAB-FWD 2>/dev/null; do
        ipt -D DOCKER-USER -i "$BRIDGE" -j ISELM1-LAB-FWD
    done
    while ipt -C INPUT -i "$BRIDGE" -j ISELM1-LAB-HOST 2>/dev/null; do
        ipt -D INPUT -i "$BRIDGE" -j ISELM1-LAB-HOST
    done
    for chain in ISELM1-LAB-FWD ISELM1-LAB-HOST; do
        ipt -F "$chain" 2>/dev/null || true
        ipt -X "$chain" 2>/dev/null || true
    done
}

if [ "${1:-}" = "stop" ]; then
    remove_rules
    echo "ISE-LM1 실습 컨테이너 방화벽 규칙을 제거했습니다."
    exit 0
fi

# ---- 1. 컨테이너 → 바깥 (FORWARD). Docker 가 FORWARD 맨 앞에서 부르는 DOCKER-USER 체인에 연결한다 ----
ipt -N ISELM1-LAB-FWD 2>/dev/null || true
ipt -F ISELM1-LAB-FWD
ipt -A ISELM1-LAB-FWD -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
# 컨테이너의 DNS 서버(OCI 는 169.254.169.254)는 53번만 허용
ipt -A ISELM1-LAB-FWD -d "$DNS_SERVER" -p udp --dport 53 -j RETURN
ipt -A ISELM1-LAB-FWD -d "$DNS_SERVER" -p tcp --dport 53 -j RETURN
# 메타데이터 서비스 등 링크 로컬 주소
reject ISELM1-LAB-FWD -d 169.254.0.0/16
if [ "$ISOLATE_CONTAINERS" = "1" ]; then
    # 같은 브리지의 다른 컨테이너 (다른 수강생의 실습 컨테이너)
    reject ISELM1-LAB-FWD -o "$BRIDGE"
fi
if [ "$BLOCK_PRIVATE" = "1" ]; then
    for net in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 100.64.0.0/10; do
        reject ISELM1-LAB-FWD ! -o "$BRIDGE" -d "$net"
    done
fi

ipt -N DOCKER-USER 2>/dev/null || true
ipt -C DOCKER-USER -i "$BRIDGE" -j ISELM1-LAB-FWD 2>/dev/null || ipt -I DOCKER-USER 1 -i "$BRIDGE" -j ISELM1-LAB-FWD

# ---- 1-1. Tailscale 등 CGNAT 대역(100.64.0.0/10) ----
# Tailscale 은 FORWARD 맨 앞의 ts-forward 에서 "-o tailscale0 -j ACCEPT" 로 먼저 허용하므로 DOCKER-USER 까지 오지 않는다.
# 그보다 앞서 처리되는 mangle PREROUTING 에서 버린다 (Tailscale 이 규칙을 다시 만들어도 영향 없음).
ipt -t mangle -N ISELM1-LAB-PRE 2>/dev/null || true
ipt -t mangle -F ISELM1-LAB-PRE
if [ "$BLOCK_PRIVATE" = "1" ]; then
    ipt -t mangle -A ISELM1-LAB-PRE -d 100.64.0.0/10 -j DROP
fi
ipt -t mangle -C PREROUTING -i "$BRIDGE" -j ISELM1-LAB-PRE 2>/dev/null || ipt -t mangle -I PREROUTING 1 -i "$BRIDGE" -j ISELM1-LAB-PRE

# ---- 2. 컨테이너 → 호스트 자신 (INPUT). 호스트의 어떤 서비스에도 새로 연결하지 못하게 한다 ----
ipt -N ISELM1-LAB-HOST 2>/dev/null || true
ipt -F ISELM1-LAB-HOST
ipt -A ISELM1-LAB-HOST -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
reject ISELM1-LAB-HOST

ipt -C INPUT -i "$BRIDGE" -j ISELM1-LAB-HOST 2>/dev/null || ipt -I INPUT 1 -i "$BRIDGE" -j ISELM1-LAB-HOST

echo "ISE-LM1 실습 컨테이너 방화벽 규칙을 적용했습니다 (bridge=$BRIDGE, dns=$DNS_SERVER, isolate=$ISOLATE_CONTAINERS, private=$BLOCK_PRIVATE)."
