#!/bin/sh
# 실습 컨테이너에서 apt 로 패키지를 설치하지 못하게 한다.
# 백엔드(LabContainerSetup)가 컨테이너를 시작한 직후, 사용자 셸을 붙이기 전에 root 로 한 번 실행한다.
# 실습 사용자는 sudo 로 root 가 될 수 있으므로 설정 파일이 아니라 바이너리와 다운로드 수단 자체를 없앤다.
set -eu

MSG='이 실습 환경에서는 패키지를 설치할 수 없습니다.'

# 1. apt 계열 명령을 안내 메시지만 출력하는 스크립트로 바꾼다 (원본 바이너리는 남기지 않는다)
for name in apt apt-get aptitude; do
    printf '#!/bin/sh\necho "%s: %s" >&2\nexit 100\n' "$name" "$MSG" > "/usr/bin/$name"
    chmod 755 "/usr/bin/$name"
done

# 2. 저장소 목록, 받아 둔 패키지 목록, 다운로드 방법(http/https 등)을 지운다
rm -f /etc/apt/sources.list
rm -rf /etc/apt/sources.list.d/* /var/lib/apt/lists/* /var/cache/apt/archives/*.deb /usr/lib/apt/methods

# 3. dpkg 는 조회 명령(dpkg -l 등)만 허용한다. 리눅스마스터 실습에서 패키지 조회는 쓰기 때문에 남겨 둔다.
mkdir -p /usr/lib/iselm1
if [ ! -e /usr/lib/iselm1/dpkg ]; then
    mv /usr/bin/dpkg /usr/lib/iselm1/dpkg
fi
cat > /usr/bin/dpkg <<'EOF'
#!/bin/sh
case "${1:-}" in
    -l|--list|-L|--listfiles|-s|--status|-S|--search|-p|--print-avail|--get-selections|\
    --print-architecture|--print-foreign-architectures|--compare-versions|--version|--help|-\?)
        exec /usr/lib/iselm1/dpkg "$@" ;;
esac
echo "dpkg: 이 실습 환경에서는 패키지를 설치하거나 변경할 수 없습니다. (조회: dpkg -l, -L, -s, -S)" >&2
exit 2
EOF
chmod 755 /usr/bin/dpkg
