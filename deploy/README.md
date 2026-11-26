# 실습 서버 / 배포 설정

실습 서버(Docker 호스트)와 배포 서버에서 직접 적용하는 설정입니다. 백엔드 코드는 이 파일들을 사용하지 않습니다.
서버 주소는 이 저장소에 적지 않습니다. 아래의 `<서버 주소>`, `example.com` 은 실제 값으로 바꿉니다.

| 폴더 | 내용 | 적용 위치 |
|---|---|---|
| `db/` | 회원 DB(PostgreSQL 17) 컨테이너 실행 스크립트 | 실습 서버 `~/iselm1-db` |
| `lab-image/` | 실습 컨테이너 이미지 (`sec-lab-ubuntu`) Dockerfile | 실습 서버 `~/sec-lab-image` |
| `lab-server/` | 실습 컨테이너 방화벽 (메타데이터·호스트·다른 컨테이너 접근 차단) | 실습 서버 |
| `docker-tls/` | Docker API TLS 인증서 생성 스크립트, dockerd 설정 | 실습 서버 + 백엔드 PC |
| `app/` | 백엔드 systemd 서비스, 서버용 설정 예시 | 실습 서버(배포) |
| `nginx/` | 프론트·백엔드를 한 도메인으로 묶는 Nginx 설정 (인증서 발급 전 HTTP / 발급 후 HTTPS) | 실습 서버(배포) |

## DB. 회원 DB (PostgreSQL)

```bash
mkdir -p ~/iselm1-db && cp run-postgres.sh ~/iselm1-db/ && ~/iselm1-db/run-postgres.sh
```

| 항목 | 값 |
|---|---|
| 컨테이너 | `iselm1-postgres` (`postgres:17`, `--restart unless-stopped`, 메모리 1GB) |
| 네트워크 / 데이터 | Docker 네트워크 `iselm1-db`, 볼륨 `iselm1-pgdata` (컨테이너를 지워도 유지) |
| 접속 정보 | `~/iselm1-db/.env` (권한 600, 처음 실행 시 비밀번호 무작위 생성). 같은 값을 백엔드 `application-local.properties` 의 `spring.datasource.*` 에 넣습니다 |
| 접근 | 5432 는 Tailscale(`tailscale0`)에서만 허용 (방화벽 스크립트의 `PRIVATE_PORTS`). 실습 컨테이너에서는 닿지 않습니다 |

- 테이블은 백엔드가 시작할 때 Flyway 로 만듭니다(`src/main/resources/db/migration`).
- 백업 예: `sudo docker exec iselm1-postgres pg_dump -U iselm1 iselm1 > iselm1-$(date +%F).sql`
- 실습 컨테이너 정리 라벨(`iselm1.lab`)을 붙이지 않으므로 백엔드가 시작할 때 지워지지 않습니다.

## 0. 실습 컨테이너 이미지

`lab-image/` 의 파일을 실습 서버 `~/sec-lab-image` 에 두고 빌드합니다. 태그를 올려서 빌드하고, 이전 태그는 되돌릴 때를 위해 남겨 둡니다.

```bash
cd ~/sec-lab-image
sudo docker build -t sec-lab-ubuntu:1.2 .
```

| 구분 | 패키지 |
|---|---|
| 기본 | bash, sudo, vim, nano, curl, wget, ca-certificates, python3 |
| 네트워크 도구 | net-tools, iputils-ping, iproute2, dnsutils, nmap, tcpdump, netcat-openbsd, openssh-client |
| 실습용 서비스 | cron, rsyslog, bind9(+bind9-utils), vsftpd, apache2, acct(lastcomm, sa, accton) |

- 컨테이너가 만들어지면 백엔드가 `lab-init`(→ `/usr/local/sbin/lab-init`)을 root 로 실행해 rsyslog 와 프로세스 accounting(`accton /var/log/account/pacct`)을 켭니다. 나머지는 실습에서 `sudo service cron|named|vsftpd|apache2 start` 로 켭니다 (bind9 의 서비스 이름은 `named`).
- 프로세스 accounting 은 컨테이너에 `CAP_SYS_PACCT` 가 있어야 합니다 (백엔드 `lab.container.cap-add`, 기본 `SYS_PACCT`). `lastcomm`, `sa` 는 `sudo` 로 실행합니다.

| 실습 항목 | 구성 |
|---|---|
| `/etc/securetty` | console, tty1~6, ttyS0~1 (`securetty`). `/etc/pam.d/login` 에 `pam_securetty.so` 를 넣어 pts 에서 root 로그인을 거부합니다 (`sudo login root` → `/var/log/secure` 에 `tty pts/N is not secure`) |
| `/var/log/secure` | `authpriv.*` (`60-iselm1.conf`). Ubuntu 의 `/var/log/auth.log` 와 같은 내용 |
| `/var/log/sulog` | `su` 의 syslog 기록 (`(to root) student on pts/0`, `FAILED SU ...`). util-linux `su` 는 `login.defs` 의 `SULOG_FILE` 을 지원하지 않아 rsyslog 로 모읍니다 |
| `su` | root 비밀번호 `root` (su 실습용. student 는 이미 NOPASSWD sudo 라 권한이 늘지 않음) |
| `lastcomm`, `sa` | `/var/log/account/pacct` |

- `/etc/pam.d/login` 의 `pam_lastlog.so` 줄은 주석 처리합니다 (Ubuntu 24.04 에 모듈이 없어 로그에 오류만 남음).
- 컨테이너에는 systemd 가 없습니다. rsyslog 패키지는 systemd 서비스만 있어서 `rsyslog.init` 을 `/etc/init.d/rsyslog` 로 넣고, 커널 로그 모듈(imklog)은 끕니다.
- 서버 Docker 는 BuildKit 이 없어(legacy builder) `COPY --chmod` 같은 BuildKit 문법을 쓸 수 없습니다.
- 사용자 `student`(비밀번호 `student`, NOPASSWD sudo). 이미지를 바꾸면 `lab.container.image` 태그도 바꿉니다.

## 1. 실습 컨테이너 방화벽

실습 컨테이너는 `sudo` 로 root 가 될 수 있고 인터넷에 접근할 수 있습니다. 아래 통신만 막고 인터넷(nmap, ping, curl 등)은 그대로 둡니다.

| 차단 대상 | 이유 |
|---|---|
| `169.254.0.0/16` (DNS `169.254.169.254:53` 은 허용) | OCI 인스턴스 메타데이터. 인스턴스 정보와 (instance principal 이 있으면) OCI 자격 증명이 노출됨 |
| 실습 서버 호스트 자신 | SSH, Docker API, eBPF 에이전트 등 호스트 서비스 |
| 같은 브리지의 다른 컨테이너 (`ISOLATE_CONTAINERS=1`) | 다른 수강생의 실습 컨테이너 공격 |
| 사설망 `10/8`, `172.16/12`, `192.168/16`, `100.64/10` (`BLOCK_PRIVATE=1`) | VCN 안의 다른 서버, Tailscale 망의 다른 기기 |
| Docker 로 공개한 `PRIVATE_PORTS`(기본 5432)를 `tailscale0` 밖에서 접속 | 회원 DB 는 Tailscale 에서만 접속 (`ISELM1-PRIVATE`, DNAT 뒤에도 원래 포트로 거름) |

- Tailscale 은 FORWARD 맨 앞의 `ts-forward` 에서 `tailscale0` 로 나가는 트래픽을 먼저 허용합니다. 그래서 `100.64.0.0/10` 은 그보다 앞서 처리되는 `mangle PREROUTING`(`ISELM1-LAB-PRE`)에서 버립니다.

### 적용

```bash
sudo install -m 0755 iselm1-lab-firewall.sh /usr/local/sbin/iselm1-lab-firewall.sh
sudo /usr/local/sbin/iselm1-lab-firewall.sh
```

### 영구 저장 (재부팅·Docker 재시작 후에도 유지)

```bash
sudo install -m 0644 iselm1-lab-firewall.service /etc/systemd/system/iselm1-lab-firewall.service
sudo systemctl daemon-reload
sudo systemctl enable --now iselm1-lab-firewall.service
systemctl status iselm1-lab-firewall.service
```

- Docker 가 체인을 만든 뒤(`After=docker.service`) 적용하고, Docker 를 재시작하면 함께 다시 적용합니다(`PartOf`).
- `netfilter-persistent save`(iptables-persistent)로 저장하지 않습니다. Docker 가 만든 규칙까지 함께 저장되어, 부팅 때 Docker 보다 먼저 복원되면서 규칙이 꼬일 수 있습니다.
- 끄기: `sudo systemctl disable --now iselm1-lab-firewall.service` (규칙도 제거됨)

### 확인

```bash
sudo iptables -S ISELM1-LAB-FWD
sudo iptables -S DOCKER-USER | head -3      # 맨 앞에 -i docker0 -j ISELM1-LAB-FWD
sudo iptables -S INPUT | head -3            # 맨 앞에 -i docker0 -j ISELM1-LAB-HOST
sudo iptables -t mangle -S PREROUTING | head -2   # -i docker0 -j ISELM1-LAB-PRE
```

실습 터미널에서:

| 명령 | 기대 결과 |
|---|---|
| `curl -m 5 -H "Authorization: Bearer Oracle" http://169.254.169.254/opc/v2/instance/` | 실패 (`No route to host` 등) |
| `dig example.com` / `ping -c 2 8.8.8.8` / `curl -I http://example.com` | 정상 |
| `nmap -Pn -p 80,443 scanme.nmap.org` | 정상 |
| `ssh 172.17.0.1` (호스트) | 실패 |

- 다른 서비스 컨테이너가 기본 브리지(`docker0`)에서 서로 통신해야 하면 `ISOLATE_CONTAINERS=0` 으로 실행합니다. 서비스 파일에서는 `[Service]` 에 `Environment=ISOLATE_CONTAINERS=0` 을 추가합니다.
- 컨테이너의 DNS 서버가 다르면(`docker run --rm sec-lab-ubuntu:1.2 cat /etc/resolv.conf`) `DNS_SERVER` 로 지정합니다.
- 이 서버는 `netfilter-persistent` 가 켜져 있고 `/etc/iptables/rules.v4` 에 Docker·Tailscale 체인까지 저장되어 있습니다. 이 방화벽 규칙은 그 파일에 넣지 않고 systemd 서비스로만 적용합니다.
- IPv6 는 Docker 기본값(사용 안 함) 기준입니다. Docker IPv6 를 켜면 `ip6tables` 규칙도 따로 필요합니다.

## 2. Docker API TLS (2375 → 2376)

인증 없는 `2375` 는 접근할 수 있는 누구에게나 서버 root 권한을 줍니다. TLS 클라이언트 인증서가 있는 백엔드만 접속하게 바꿉니다.
Docker 를 재시작하면 실행 중인 실습 컨테이너가 종료되므로 실습이 없을 때 진행합니다.

| 순서 | 위치 | 작업 |
|---|---|---|
| 1 | 실습 서버 | `./gen-certs.sh <서버 주소>` 로 인증서 생성 (백엔드가 접속하는 공인 IP 또는 도메인) |
| 2 | 실습 서버 | `sudo install -d -m 0700 /etc/docker/tls && sudo cp docker-tls/server/* /etc/docker/tls/` |
| 3 | 백엔드 PC | `docker-tls/client/` 의 `ca.pem`, `cert.pem`, `key.pem` 을 복사 (예: `C:/Users/<사용자>/.docker/iselm1/`). git 저장소 안에 두지 않습니다 |
| 4 | 실습 서버 | 기존 2375 설정 확인: `systemctl cat docker`, `cat /etc/docker/daemon.json`. `-H tcp://...:2375` 나 `"hosts"` 를 지웁니다 |
| 5 | 실습 서버 | `override.conf` 를 `/etc/systemd/system/docker.service.d/override.conf` 로 복사 → `sudo systemctl daemon-reload && sudo systemctl restart docker` |
| 6 | 실습 서버 | 확인: `docker --tlsverify --tlscacert=/etc/docker/tls/ca.pem --tlscert=<client>/cert.pem --tlskey=<client>/key.pem -H tcp://127.0.0.1:2376 version` |
| 7 | 실습 서버 | 호스트 방화벽에서 2376 허용, 2375 규칙 제거 (아래) |
| 8 | OCI 콘솔 | 보안 목록에서 2375 규칙 삭제, 2376 을 백엔드 IP(`/32`)에서만 허용 (아래) |
| 9 | 백엔드 PC | `application-local.properties` 수정 (아래) 후 백엔드 재시작, `GET /api/docker/health` 가 `UP` 인지 확인 |

호스트 방화벽 (OCI Ubuntu 이미지는 `/etc/iptables/rules.v4` 로 INPUT 을 관리합니다):

```bash
sudo iptables -S INPUT | grep -E '2375|2376'                        # 기존 규칙 확인
sudo iptables -I INPUT -p tcp -m state --state NEW --dport 2376 -j ACCEPT
sudo iptables -D INPUT -p tcp -m state --state NEW --dport 2375 -j ACCEPT   # 기존 규칙과 같은 형태로 삭제
# 영구 저장: /etc/iptables/rules.v4 에서 2375 줄을 2376 으로 바꾼다 (netfilter-persistent save 는 쓰지 않는다)
```

OCI 콘솔: **네트워킹 → 가상 클라우드 네트워크 → (VCN) → 보안 목록(또는 NSG) → 수신 규칙**

| 작업 | 소스 CIDR | 프로토콜 | 대상 포트 |
|---|---|---|---|
| 삭제 | (기존 2375 규칙) | TCP | 2375 |
| 추가 | `<백엔드 공인 IP>/32` | TCP | 2376 |

- 백엔드 PC 의 공인 IP 가 바뀌면 접속이 막힙니다. TLS 로 인증하므로 소스를 넓혀도 인증서 없는 접속은 거부되지만, 가능한 한 좁게 둡니다.

`application-local.properties`:

```properties
lab.docker.host=tcp://<서버 주소>:2376
lab.docker.tls-verify=true
lab.docker.cert-path=C:/Users/<사용자>/.docker/iselm1
```

## 3. 배포 (Nginx + HTTPS)

백엔드를 실습 서버에 함께 올리고, Nginx 로 프론트엔드와 같은 도메인에 묶습니다. 같은 Origin 이 되어 CORS 가 단순해지고, `crypto.randomUUID()`·클립보드가 필요로 하는 HTTPS 가 됩니다.
사이트 주소는 `https://www.<도메인>` 하나이고, `<도메인>`(www 없음)과 http 는 모두 그 주소로 보냅니다.

```
브라우저 ──HTTPS──▶ Nginx(443) ─┬─ /                       → /var/www/iselm1 (프론트 dist)
                                └─ /api /ws /oauth2 /login/oauth2 → 127.0.0.1:8080 (백엔드)
백엔드 ─┬─ Docker   unix:///var/run/docker.sock
        ├─ DB       127.0.0.1:5432
        └─ eBPF     127.0.0.1:9000
```

| 파일 | 서버 위치 |
|---|---|
| `app/iselm1-backend.service` | `/etc/systemd/system/iselm1-backend.service` (사용자 `iselm1`, docker 그룹) |
| `app/application-local.properties.example` | `/opt/iselm1/backend/application-local.properties` (권한 600, 비밀 값은 개발 PC 설정과 같음) |
| 백엔드 jar (`./gradlew bootJar`) | `/opt/iselm1/backend/app.jar` |
| 학습 문서 `docs/` (`.obsidian` 제외) | `/opt/iselm1/backend/docs/` |
| 프론트 (`VITE_API_BASE_URL=https://www.<도메인> npm run build`) | `/var/www/iselm1/` |
| `nginx/iselm1-http.conf.example` → 인증서 발급 후 `nginx/iselm1.conf.example` | `/etc/nginx/sites-available/iselm1.conf` (+ `sites-enabled` 링크, `default` 링크 제거) |

### 처음 설치

| 순서 | 작업 |
|---|---|
| 1 | DNS: `www` 와 `@`(도메인 자체) 모두 A 레코드로 서버 공인 IP |
| 2 | OCI 보안 목록 수신 규칙에 TCP 80, 443 (소스 `0.0.0.0/0`) |
| 3 | 호스트 방화벽: INPUT 의 80 규칙 다음에 443 허용을 넣고, `/etc/iptables/rules.v4` 에도 같은 줄 추가 (`netfilter-persistent save` 는 쓰지 않음) |
| 4 | `sudo apt-get install openjdk-17-jre-headless nginx certbot` |
| 5 | `sudo useradd --system --home-dir /opt/iselm1 --shell /usr/sbin/nologin iselm1 && sudo usermod -aG docker iselm1` |
| 6 | 위 표대로 파일을 두고 `sudo systemctl enable --now iselm1-backend` |
| 7 | 임시 HTTP 설정(`iselm1-http.conf.example`)으로 Nginx 시작 → `sudo certbot certonly --webroot -w /var/www/certbot -d www.<도메인> -d <도메인>` |
| 8 | HTTPS 설정(`iselm1.conf.example`)으로 바꾸고 `sudo nginx -t && sudo systemctl reload nginx`. 인증서 갱신 후 Nginx 를 다시 읽게 `/etc/letsencrypt/renewal-hooks/deploy/` 에 `systemctl reload nginx` 스크립트 |
| 9 | 구글 OAuth 클라이언트에 리디렉션 URI `https://www.<도메인>/login/oauth2/code/google` 추가 |

- `apt` 로 패키지를 설치할 때 needrestart 가 Docker 를 재시작하면 실습 컨테이너가 종료됩니다(live-restore 꺼짐). 실습이 없을 때 설치하고, 재시작 대상 목록을 확인합니다.
- 백엔드 로그: `sudo journalctl -u iselm1-backend -f`
- `/ws/` 는 WebSocket 업그레이드 헤더를 넘기고 `proxy_read_timeout` 을 실습 세션(10분)보다 길게 둡니다. `/api/logs`(SSE)는 `proxy_buffering off`.
- 백엔드는 `server.address=127.0.0.1` 로 Nginx 에서만 접속합니다.

### 자동 배포 (GitHub push → 서버)

서버가 1분마다 GitHub 의 `main` 을 확인하고, 새 커밋이 있으면 서버에서 테스트·빌드한 뒤 배포합니다. GitHub 에 비밀 값(SSH 키 등)을 두지 않고 서버로 들어오는 포트도 열지 않습니다.
저장소가 공개라서 서버에 GitHub Actions self-hosted runner 를 두지 않습니다(포크의 PR 이 서버에서 실행될 수 있음).

| 파일 | 서버 위치 |
|---|---|
| `app/iselm1-deploy.sh` | `/usr/local/sbin/iselm1-deploy.sh` |
| `app/iselm1-deploy.service`, `app/iselm1-deploy.timer` | `/etc/systemd/system/` (`sudo systemctl enable --now iselm1-deploy.timer`) |
| `app/iselm1-deploy.env.example` | `/etc/iselm1-deploy.env` (`SITE_URL=https://www.<도메인>`) |

| 저장소 | 새 커밋이 오면 | 실패하면 |
|---|---|---|
| 백엔드 | `./gradlew test bootJar` → `app.jar` 교체 → 서비스 재시작 → 응답 확인 | 테스트·빌드 실패: 배포 안 함. 재시작 후 3분 안에 응답이 없으면 이전 jar(`app.jar.prev`)로 되돌림 |
| 프론트 | `npm ci && npm test && npm run build` → `/var/www/iselm1` 교체 (이전 것은 `/var/www/iselm1.prev`) | 테스트·빌드 실패: 배포 안 함 |
| 학습 문서 (`DOCS_REPO` 지정 시) | 빌드 없이 추적 중인 파일만(`git archive`) `/opt/iselm1/backend/docs/isec` 교체 (이전 것은 `isec.prev`), 재시작 없음. 백엔드·프론트보다 먼저 처리 | 받기 실패: 다음 분에 다시 시도 |

- 빌드는 전용 계정 `iselm1-ci`(홈 `/opt/iselm1/ci`, 작업 폴더 `backend/`, `frontend/`)로 하고, 파일 교체·재시작만 root 로 합니다. 빌드에는 `openjdk-17-jdk-headless`, `nodejs`, `npm` 이 필요합니다.
- 실패한 커밋은 다시 시도하지 않습니다. 고쳐서 다시 push 하면 그 커밋을 배포합니다.
- **백엔드가 배포되면 진행 중인 실습이 끊깁니다** (서비스 재시작).
- 상태·기록: `/var/lib/iselm1-deploy/{backend,frontend,docs}.last`(마지막으로 처리한 커밋), `*.history`. 로그: `journalctl -u iselm1-deploy`

| 명령 (서버) | 용도 |
|---|---|
| `sudo iselm1-deploy.sh` | 지금 바로 확인·배포 |
| `sudo FORCE=1 iselm1-deploy.sh` | 새 커밋이 없어도 지금 `main` 을 다시 배포 |
| `sudo DRY_RUN=1 FORCE=1 iselm1-deploy.sh` | 테스트·빌드만 (배포·기록 안 함) |
| `sudo MARK_ONLY=1 iselm1-deploy.sh` | 지금 `main` 을 배포한 것으로 기록만 (처음 설치할 때) |
| `sudo systemctl stop iselm1-deploy.timer` | 자동 배포 멈춤 |

### 학습 문서 올리기

학습 문서(`docs/`)는 백엔드 저장소에 없습니다(`.gitignore`). `docs/isec` 를 **별도 비공개 저장소**(저장소 루트 = `isec` 폴더, `.obsidian/`·`.trash/` 제외)로 두면 push 로 배포됩니다.

| 설정 (서버, 한 번만) | 내용 |
|---|---|
| 배포 키 | `sudo -u iselm1-ci ssh-keygen -t ed25519 -N "" -f /opt/iselm1/ci/.ssh/iselm1-docs` → 공개키를 문서 저장소 Settings → Deploy keys 에 등록(쓰기 권한 없음) |
| GitHub 호스트 키 | `ssh-keyscan -t ed25519 github.com` 결과를 지문(`SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU`) 확인 후 `/opt/iselm1/ci/.ssh/known_hosts`(iselm1-ci, 600) |
| `/etc/iselm1-deploy.env` | `DOCS_REPO=git@github.com:<계정>/<문서 저장소>.git` (키 위치를 바꾸면 `DOCS_SSH_KEY`) |

push 없이 개발 PC(Git Bash)에서 바로 올릴 수도 있습니다. 다음 문서 커밋이 배포되면 `isec` 는 저장소 내용으로 바뀝니다.

```bash
backend/deploy/upload-docs.sh
```

- `.obsidian`, `.trash` 는 빼고 올리고, 서버의 `docs/` 를 통째로 바꿉니다(이전 것은 `docs.prev`). 재시작은 필요 없습니다.
- 서버 접속: `lab.docker.host` 와 같은 호스트(Tailscale), PuTTY `plink`/`pscp`, 키 `~/.ssh/iselm1_lab.ppk`(`ISELM1_SSH_KEY` 로 바꿀 수 있음).

### 수동 업데이트


| 대상 | 작업 |
|---|---|
| 백엔드 | `app.jar` 를 바꾸고 `sudo systemctl restart iselm1-backend` (진행 중인 실습은 종료됨). 보통은 자동 배포를 씁니다 |
| 프론트 | `/var/www/iselm1/` 내용을 새 `dist/` 로 바꿈 (재시작 불필요). 보통은 자동 배포를 씁니다 |
| 문서 | `upload-docs.sh` (위) |

### 개발 PC 백엔드와 함께 쓸 때

배포 서버(`lab.container.instance=prod`)와 개발 PC 백엔드가 같은 Docker 를 씁니다. 각 백엔드는 시작할 때 자기 이름(`iselm1.lab.instance` 라벨)의 실습 컨테이너만 정리하므로 서로의 실습을 지우지 않습니다. 개발 PC 는 `lab.container.instance` 를 비우면 컴퓨터 이름을 씁니다. 이 라벨이 없는 예전 백엔드 코드로 개발 서버를 띄우면 배포 서버의 실습까지 지우므로, 개발 PC 백엔드도 최신 코드로 실행합니다.
