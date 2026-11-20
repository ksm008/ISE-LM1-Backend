#!/bin/sh
# Docker Engine API TLS 인증서 생성 (CA, 서버, 클라이언트). 실습 서버에서 실행한다.
#
#   ./gen-certs.sh <백엔드가 접속할 서버 주소(공인 IP 또는 도메인)> [출력 디렉터리]
#
# 결과
#   <출력>/ca-key.pem            CA 개인 키. 새 인증서를 만들 때만 필요하다. 안전한 곳에 보관한다.
#   <출력>/server/               실습 서버 /etc/docker/tls/ 로 복사
#   <출력>/client/               백엔드 PC 로 복사 (lab.docker.cert-path). docker-java 는 ca.pem, cert.pem, key.pem 이름을 쓴다.
set -eu

HOST="${1:?서버 주소(공인 IP 또는 도메인)를 지정하세요. 예: ./gen-certs.sh lab.example.com}"
OUT="${2:-./docker-tls}"
DAYS="${DAYS:-825}"

if [ -e "$OUT/ca.pem" ]; then
    echo "$OUT 에 이미 인증서가 있습니다. 다른 디렉터리를 지정하세요." >&2
    exit 1
fi

umask 077
mkdir -p "$OUT/server" "$OUT/client"
cd "$OUT"

case "$HOST" in
    *[!0-9.]*) SAN="DNS:$HOST" ;;
    *) SAN="IP:$HOST" ;;
esac

# CA
openssl genrsa -out ca-key.pem 4096
openssl req -new -x509 -days "$DAYS" -sha256 -key ca-key.pem -subj "/CN=iselm1-docker-ca" -out ca.pem

# 서버 인증서 (Docker 데몬)
openssl genrsa -out server/server-key.pem 4096
openssl req -new -key server/server-key.pem -subj "/CN=$HOST" -out server.csr
printf 'subjectAltName = %s,IP:127.0.0.1\nextendedKeyUsage = serverAuth\n' "$SAN" > server-ext.cnf
openssl x509 -req -days "$DAYS" -sha256 -in server.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial \
    -out server/server-cert.pem -extfile server-ext.cnf
cp ca.pem server/ca.pem

# 클라이언트 인증서 (백엔드)
openssl genrsa -out client/key.pem 4096
openssl req -new -key client/key.pem -subj "/CN=iselm1-backend" -out client.csr
printf 'extendedKeyUsage = clientAuth\n' > client-ext.cnf
openssl x509 -req -days "$DAYS" -sha256 -in client.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial \
    -out client/cert.pem -extfile client-ext.cnf
cp ca.pem client/ca.pem

rm -f server.csr client.csr server-ext.cnf client-ext.cnf ca.srl
chmod 0400 ca-key.pem server/server-key.pem client/key.pem
chmod 0444 ca.pem server/ca.pem server/server-cert.pem client/ca.pem client/cert.pem

echo "인증서를 만들었습니다: $(pwd)"
echo "  서버용: server/ (ca.pem, server-cert.pem, server-key.pem) → /etc/docker/tls/"
echo "  백엔드용: client/ (ca.pem, cert.pem, key.pem) → 백엔드 PC, lab.docker.cert-path 로 지정"
