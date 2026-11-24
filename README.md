# ISE-LM1 Backend

정보보안기사와 리눅스마스터 1급 시험을 준비하기 위한 학습 사이트의 백엔드입니다.
프론트엔드([ISE-LM1-Frontend](https://github.com/ksm008/ISE-LM1-Frontend))의 터미널 실습에 필요한 실습용 리눅스 컨테이너를 만들고, 브라우저와 컨테이너 쉘을 연결하며, eBPF 모니터링 로그를 실시간으로 전달합니다.

## 주요 기능

- **회원 (로그인, 회원가입, 이메일 인증, 구글 연동)**
  - 아이디/비밀번호 회원가입. 비밀번호는 8\~20자, 영문 대문자·소문자·숫자·특수문자를 모두 포함해야 합니다. 아이디는 4\~20자, 영문 소문자로 시작하고 영문 소문자·숫자·_ 만 씁니다.
  - 가입하면 이메일로 숫자 6자리 인증번호를 보냅니다(10분 유효, 5회까지 입력, 60초 후 다시 보내기). 인증을 마쳐야 사이트를 이용할 수 있습니다.
  - 구글 로그인은 가입한 계정에 연동해 쓰는 기능입니다. 연동된 계정이 없는 구글 계정으로 로그인하면 아이디/비밀번호만 정해서 가입하고, 그 구글 계정의 Gmail 로 인증번호를 보냅니다.
  - 관리자 계정은 설정(`auth.admin.*`)으로 서버 시작 시 만들어지며 이메일 인증 없이 모든 콘텐츠를 이용할 수 있습니다.
  - 로그인·회원가입·인증·로그인 상태 확인을 뺀 모든 API 와 실습 WebSocket 은 로그인(인증 완료)이 필요합니다. 세션 쿠키 로그인이며, 연속 로그인 실패 시 잠시 잠급니다.
  - 회원 정보는 PostgreSQL(실습 서버 컨테이너)에 저장하고, 테이블은 Flyway(`src/main/resources/db/migration`)로 관리합니다.
- **터미널 실습 (WebSocket)**
  - 실습을 시작하면 원격 Docker 호스트에 실습용 컨테이너(`sec-lab-ubuntu:1.2`)를 새로 생성합니다.
  - 실습 이미지에는 네트워크 도구(nmap, tcpdump, dig 등)와 실습용 서비스 cron, rsyslog, bind9(named), vsftpd, apache2 가 설치되어 있습니다. rsyslog 와 프로세스 accounting(`lastcomm`)은 컨테이너가 만들어질 때 자동으로 켜지고, 나머지 서비스는 `sudo service <이름> start` 로 켭니다. `/etc/securetty`(pam_securetty), `/var/log/secure`, `/var/log/sulog` 도 준비되어 있습니다. 이미지 정의는 `deploy/lab-image/` 에 있습니다.
  - 컨테이너의 `/bin/bash` 쉘을 WebSocket으로 연결해 브라우저에서 바로 사용할 수 있습니다.
  - WebSocket 연결이 종료되거나 쉘이 끝나면(`exit`) 컨테이너를 자동으로 삭제합니다.
  - 실습 세션은 기본 10분 동안 유지되며, 갱신 요청을 받으면 그 시점부터 다시 10분으로 연장합니다.
  - 실습 컨테이너에서는 `sudo` 를 쓰더라도 `apt` 로 패키지를 설치할 수 없습니다. `dpkg` 는 조회 명령(`-l`, `-L`, `-s`, `-S` 등)만 허용합니다.
- **eBPF 모니터링 로그 (SSE)**
  - 실습 서버의 eBPF 에이전트가 수집한 컨테이너 로그를 Server-Sent Events로 실시간 전달합니다.
  - 해당 `labId` 의 SSE 구독이 있을 때만 에이전트에 연결합니다. 현재 프론트엔드는 로그 패널을 비활성화해 SSE를 구독하지 않으므로 에이전트에 연결하지 않습니다.
  - 실습이 종료되면 로그 스트림도 함께 종료합니다.
- **패킷 캡처 (WebSocket, Wireshark 실습)**
  - 실행 중인 터미널 실습 컨테이너 안에서 root 로 `tcpdump` 를 실행하고, pcap 출력을 패킷 단위로 나눠 브라우저로 보냅니다. 프로토콜 해석은 프론트엔드에서 합니다.
  - 캡처 필터(BPF)와 인터페이스를 지정할 수 있고, 패킷 크기(snaplen)와 캡처당 패킷 수를 제한합니다.
  - 터미널 실습이 끝나면 캡처도 중지하고 연결을 닫습니다.
- **학습 문서 (Markdown)**
  - 항목별 학습 문서를 `docs/` 폴더(`docs.root`)의 `.md` 파일로 두고, 로그인(인증 완료)한 회원에게 API 로 제공합니다.
  - 문서 폴더는 git 에 올리지 않습니다(`.gitignore`). 요청마다 파일을 읽으므로 파일을 고치면 서버를 재시작하지 않아도 반영됩니다.
- **Docker 연결 확인**: 컨테이너를 만들지 않고 원격 Docker 호스트 연결 상태만 확인하는 API를 제공합니다.
- **실습 자원 보호**
  - 동시 실습 수, 컨테이너 메모리·CPU·프로세스 수를 제한합니다.
  - 세션 시간이 만료되면 터미널 연결을 끊고 컨테이너를 삭제합니다.
  - 서버 종료 시, 그리고 비정상 종료 후 다시 시작할 때 남은 실습 컨테이너를 정리합니다. 같은 Docker 를 쓰는 다른 백엔드의 실습은 건드리지 않습니다(`lab.container.instance`).

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어 | Java 17 |
| 프레임워크 | Spring Boot 4.1.2 (Web MVC, WebSocket) |
| 빌드 도구 | Gradle |
| Docker 연동 | docker-java 3.3.6 (Apache HttpClient5 transport) |
| 기타 | Lombok |

## 시작하기

JDK 17 이상이 필요합니다.

```bash
./gradlew bootRun
```

서버가 실행되면 `http://localhost:8080` 에서 요청을 받습니다. 프론트엔드의 `VITE_API_BASE_URL` 기본값과 같은 주소입니다.

| 명령어 | 설명 |
|---|---|
| `./gradlew bootRun` | 개발 서버 실행 |
| `./gradlew build` | 빌드 및 테스트 (`build/libs/` 에 jar 생성) |
| `./gradlew test` | 테스트 실행 |

Windows에서는 `./gradlew` 대신 `gradlew.bat` 을 사용합니다.

### 개인 설정 (application-local.properties)

아래 값은 git 에 올리지 않고 `application-local.properties` 에 둡니다.

| 설정 | 설명 |
|---|---|
| `spring.datasource.url`, `username`, `password` | 회원 DB (실습 서버 PostgreSQL, Tailscale 로 접속) |
| `auth.admin.username`, `auth.admin.password`, `auth.admin.email` | 관리자 계정. 서버 시작 시 없으면 만들고 있으면 권한·비밀번호를 맞춤 |
| `spring.mail.host`, `port`, `username`, `password`, `auth.mail.from` | 인증번호 메일 SMTP. `spring.mail.host` 가 없으면 인증번호를 서버 로그에만 출력(개발용) |
| `auth.google.client-id`, `auth.google.client-secret` | 구글 OAuth 클라이언트. 비우면 구글 로그인이 꺼짐 |

### 학습 문서 (docs/)

| 항목 | 내용 |
|---|---|
| 위치 | `docs.root` (기본 `./docs`, 서버 실행 위치 기준). git 에 올리지 않음 |
| 구조 | `docs/{section}/{topic}/{slug}.md` 예) `docs/isec/02-vulnerability/01-web-application.md` |
| 정보보안기사 항목 폴더 | `01-system-network`, `02-vulnerability`, `03-log-response`, `04-risk-management`, `05-trends` (항목 번호 + 프론트 `curriculum.js` 의 항목 id) |
| 이름 규칙 | 폴더·파일 이름은 영문 소문자·숫자·하이픈만 (예: `01-web-application`). 목록은 파일 이름 순이므로 앞에 번호를 붙임 |
| 제목 | 파일의 첫 `# 제목` 줄 (없으면 파일 이름) |
| 인코딩 | UTF-8 (BOM 은 무시) |
| 배포 | jar 와 함께 `docs/` 폴더를 서버에 복사하거나, `docs.root`(환경 변수 `DOCS_ROOT`)로 위치 지정 |

### 실습 서버 설정

이 서버는 원격 실습 서버에 Docker Engine API와 eBPF 로그 에이전트가 실행 중이어야 동작합니다.
실습 서버 주소는 git에 올리지 않고 `application-local.properties` 에 지정합니다.

```bash
cp application-local.properties.example application-local.properties
```

```properties
lab.docker.host=tcp://<실습 서버 주소>:2376
lab.docker.tls-verify=true
lab.docker.cert-path=C:/path/to/docker-certs
lab.ebpf.agent-url=http://<실습 서버 주소>:9000
lab.cors.allowed-origins=http://localhost:5173
```

`application-local.properties` 는 프로젝트 루트(실행 위치)에 두며 git에 포함되지 않습니다. 같은 값을 환경 변수로 지정할 수도 있습니다. 점(`.`)은 `_` 로 바꾸고 하이픈(`-`)은 지웁니다(예: `LAB_DOCKER_HOST`, `LAB_DOCKER_TLSVERIFY`, `LAB_EBPF_AGENTURL`, `LAB_CORS_ALLOWEDORIGINS`).

| 설정 | 기본값 | 설명 |
|---|---|---|
| `lab.docker.host` | `tcp://localhost:2375` | Docker Engine API 주소 |
| `lab.docker.tls-verify` | `false` | Docker API TLS 인증 사용 여부 |
| `lab.docker.cert-path` | (없음) | TLS 인증서(`ca.pem`, `cert.pem`, `key.pem`) 디렉터리 |
| `lab.ebpf.agent-url` | `http://localhost:9000` | eBPF 로그 에이전트 주소 (`/stream?containerId=` 가 붙음) |
| `lab.cors.allowed-origins` | `http://localhost:5173` | 접속을 허용할 프론트엔드 Origin (쉼표로 여러 개) |
| `lab.container.instance` | (컴퓨터 이름) | 이 백엔드의 이름. 실습 컨테이너에 `iselm1.lab.instance` 라벨로 붙고, 시작할 때 이 이름의 컨테이너만 정리합니다. 같은 Docker 를 쓰는 배포 서버(`prod`)와 개발 PC 가 서로의 실습을 지우지 않게 합니다 |
| `lab.container.image` | `sec-lab-ubuntu:1.2` | 실습 컨테이너 이미지 (`deploy/lab-image/Dockerfile`) |
| `lab.container.memory-mb` | `512` | 컨테이너 메모리 제한 (스왑 없음) |
| `lab.container.cpus` | `1.0` | 컨테이너 CPU 제한 |
| `lab.container.pids-limit` | `256` | 컨테이너 프로세스 수 제한 |
| `lab.container.block-package-install` | `true` | 실습 컨테이너에서 `apt` 패키지 설치 차단 (`sudo` 포함) |
| `lab.container.cap-add` | `SYS_PACCT` | 컨테이너에 추가할 Linux capability (쉼표로 여러 개). `SYS_PACCT` 는 프로세스 accounting(`lastcomm`)용으로, PID 네임스페이스별로 동작해 호스트 프로세스는 기록되지 않습니다 |
| `lab.session.max-sessions` | `10` | 동시 실습 수 |
| `lab.session.lease` | `10m` | 실습 세션 유지 시간 (갱신하면 그 시점부터 다시 이 시간만큼 연장, 만료 시 컨테이너 삭제) |
| `lab.session.sse-timeout` | `30m` | SSE 연결 유지 시간 (만료 시 브라우저가 자동 재연결) |
| `lab.capture.snaplen` | `4096` | 패킷 하나에서 캡처하는 최대 바이트 (`tcpdump -s`) |
| `lab.capture.max-packets` | `20000` | 캡처 한 번에 받는 최대 패킷 수 (넘으면 중지) |

- 실습 서버에 `lab.container.image` 이미지가 있어야 합니다. 없으면 첫 실습을 시작할 때 이미지 다운로드를 시도합니다.
- 이 서버가 만든 컨테이너에는 `iselm1.lab=true` 라벨이 붙으며, 서버 시작 시 이 라벨이 붙은 컨테이너는 정리됩니다. 같은 Docker 호스트를 여러 백엔드 인스턴스가 함께 쓰지 않도록 합니다.

## API

| 종류 | 엔드포인트 | 설명 |
|---|---|---|
| WebSocket | `/ws/terminal?labId={labId}` | 실습 컨테이너의 쉘 입출력 |
| WebSocket | `/ws/capture?labId={labId}` | 실행 중인 실습 컨테이너의 패킷 캡처 (Wireshark 실습) |
| SSE | `/api/logs?labId={labId}` | 해당 실습의 eBPF 모니터링 로그 스트림 (`text/event-stream`, 현재 프론트엔드에서 미사용) |
| GET | `/api/docker/health` | Docker 연결 상태 (`{"status":"UP"}`, 실패 시 503 `DOWN`) |
| GET | `/api/docs/{section}/{topic}` | 학습 문서 목록 `[{"slug","title"}]` (파일 이름 순, 문서가 없으면 `[]`, 이름 형식이 틀리면 404) |
| GET | `/api/docs/{section}/{topic}/{slug}` | 학습 문서 하나 `{"slug","title","markdown"}` (없으면 404) |
| GET | `/api/auth/me` | 로그인 상태 (`authenticated`, `username`, `email`, `role`, `active`, `googleLinked` 등). 로그인 전에도 200 |
| GET | `/api/auth/config` | `{"googleEnabled":bool,"mailEnabled":bool}` |
| GET | `/api/auth/csrf` | CSRF 토큰 발급 (쿠키 `XSRF-TOKEN` 설정) |
| POST | `/api/auth/login` | `{"username","password"}`. 실패 401, 연속 5회 실패 시 5분 잠금 429 |
| POST | `/api/auth/signup` | `{"username","password","email"}`. 가입 후 로그인(인증 전)되고 인증번호 메일 발송 |
| POST | `/api/auth/email/send` | 인증번호 다시 보내기 (60초 대기, 429 + `retryAfterSeconds`) |
| POST | `/api/auth/email/verify` | `{"code":"123456"}`. 10분 유효, 5회까지. 틀리면 400 + `remainingAttempts` |
| POST | `/api/auth/logout` | 로그아웃 (204) |
| GET | `/oauth2/authorization/google` | 구글 로그인 시작 (브라우저 이동) |
| GET | `/api/auth/google/pending` | 연동 계정이 없는 구글 로그인 후 가입 화면용 `{"email","name"}` |
| POST | `/api/auth/signup/google` | `{"username","password"}`. 구글 계정 Gmail 로 가입·인증번호 발송 |
| GET | `/api/auth/google/link` | 로그인한 계정에 구글 연동 시작 (브라우저 이동, 끝나면 `/account`) |
| POST | `/api/auth/google/unlink` | 구글 연동 해제 |

- 회원 API(로그인 상태 확인·로그인·회원가입·구글 로그인)를 뺀 모든 요청은 이메일 인증을 마친 회원 또는 관리자만 쓸 수 있습니다. 로그인하지 않으면 401, 인증 전이면 403 입니다.
- 로그인은 세션 쿠키(`JSESSIONID`, HttpOnly, SameSite=Lax)로 유지합니다. 상태를 바꾸는 요청(POST)은 쿠키 `XSRF-TOKEN` 값을 헤더 `X-XSRF-TOKEN` 으로 보내야 합니다(CSRF).
- 오류 응답 본문은 `{"message":"한국어 사유", ...}` 입니다.
- `labId` 는 프론트엔드가 실습을 시작할 때 UUID로 생성해 두 연결에 같은 값을 사용합니다. UUID 형식이 아니면 거부합니다(SSE는 400, WebSocket은 1008로 종료).
- 이미 실습 중인 `labId` 로 다시 WebSocket을 연결하면 거부합니다.
- **사용자당 실습은 하나**입니다. 같은 계정으로 새 실습을 시작하면 이전 실습을 1000(`다른 창에서 새 실습을 시작해 이 실습을 종료했습니다`)으로 닫고 컨테이너를 지웁니다(이전 연결이 준비 중이어도 정리). 동시 실습 수(`lab.session.max-sessions`)는 전체 상한입니다.
- 동시 실습 수를 넘으면 SSE는 503, WebSocket은 1013(`동시 실습 수 초과`)으로 종료합니다.
- 오류가 나면 내부 오류 내용은 클라이언트에 보내지 않고 서버 로그에만 남깁니다.
- SSE는 구독 직후 `:connected` comment를 보내 연결을 바로 엽니다 (브라우저 `EventSource` 는 comment를 무시합니다).

### WebSocket 메시지

메시지는 양방향 모두 JSON입니다. 형식이 맞지 않는 메시지는 무시합니다.

| 방향 | type | 예시 | 설명 |
|---|---|---|---|
| 클라이언트 → 서버 | `input` | `{"type":"input","data":"ls\r"}` | 쉘 입력 |
| 클라이언트 → 서버 | `resize` | `{"type":"resize","cols":120,"rows":30}` | 컨테이너 TTY 크기 변경 (1~500) |
| 클라이언트 → 서버 | `renew` | `{"type":"renew"}` | 세션 시간 갱신 |
| 서버 → 클라이언트 | `output` | `{"type":"output","data":"..."}` | 쉘 출력 |
| 서버 → 클라이언트 | `session` | `{"type":"session","remainingSeconds":600}` | 쉘 준비 완료 시와 갱신 시 남은 세션 시간 |

- 서버는 쉘이 준비된 뒤 클라이언트 메시지를 처리하고, 이때 첫 `session` 메시지를 보냅니다.
- 세션이 만료되면 1000(`세션 시간 만료`), 쉘이 끝나면 1000(`셸이 종료되었습니다`)으로 연결을 닫습니다.

### 패킷 캡처 메시지

| 방향 | type | 예시 | 설명 |
|---|---|---|---|
| 클라이언트 → 서버 | `start` | `{"type":"start","interface":"any","filter":"icmp"}` | 캡처 시작 (진행 중이면 중지 후 새로 시작). 필터는 tcpdump(BPF) 문법 |
| 클라이언트 → 서버 | `stop` | `{"type":"stop"}` | 캡처 중지 |
| 서버 → 클라이언트 | `ready` | `{"type":"ready","interfaces":["any","eth0","lo"],"snaplen":4096,"maxPackets":20000}` | 연결 직후 |
| 서버 → 클라이언트 | `started` | `{"type":"started","linkType":276,"interface":"any","filter":""}` | pcap 링크 타입 (276 = Linux cooked v2, 1 = Ethernet) |
| 서버 → 클라이언트 | `packets` | `{"type":"packets","packets":[{"no":1,"s":1732070000,"us":123456,"len":98,"data":"<base64>"}]}` | 약 0.2초마다 묶어서 전송 |
| 서버 → 클라이언트 | `stopped` | `{"type":"stopped","reason":"...","error":false,"packets":43}` | 중지, 필터 오류, 최대 패킷 수 도달, 실습 종료 |
| 서버 → 클라이언트 | `error` | `{"type":"error","message":"..."}` | 잘못된 인터페이스·필터 (캡처 상태는 그대로) |

- 셸 연결까지 끝난 터미널 실습의 `labId` 로만, 그 실습을 시작한 계정만 연결할 수 있고(아니면 1008, `이 실습을 캡처할 권한이 없습니다`), `labId` 하나에 캡처 연결은 하나입니다. 같은 `labId` 로 새 연결이 오면 이전 연결의 캡처를 멈추고 1000(`다른 창에서 캡처 화면을 열었습니다`)으로 닫은 뒤 새 연결이 이어받습니다.
- tcpdump 는 `-n`(이름 조회 안 함)으로 실행해 캡처 때문에 DNS 트래픽이 생기지 않게 하고, 필터 앞에 `--` 를 붙여 옵션으로 해석되지 않게 합니다.

### 실습 흐름

1. (eBPF 로그를 쓸 때만) 프론트엔드가 `/api/logs?labId={labId}` 로 SSE 구독을 엽니다.
2. 같은 `labId` 로 `/ws/terminal?labId={labId}` 에 WebSocket을 연결합니다. eBPF 로그를 쓸 때는 SSE가 열린 뒤에 연결합니다 (WebSocket 연결 시점에 구독이 없으면 로그 스트림을 시작하지 않습니다).
3. 백엔드가 실습 컨테이너를 생성하고 root 로 준비 작업을 실행합니다. 이미지의 `/usr/local/sbin/lab-init`(rsyslog·프로세스 accounting 시작, 실패해도 계속)과 패키지 설치 차단 스크립트(`src/main/resources/lab/block-package-install.sh`, 실패하면 실습을 시작하지 않음)입니다.
4. SSE 구독이 있으면 eBPF 에이전트에 해당 컨테이너의 로그 스트림을 요청하고, 쉘을 연결합니다.
5. 쉘 출력은 WebSocket으로, eBPF 로그는 SSE로 전달됩니다.
6. WebSocket 연결이 종료되면 컨테이너를 삭제하고 SSE 연결도 종료합니다.

## 프로젝트 구조

```
src/main/java/iselm1/
├── IseLm1Application.java             # 엔트리
├── auth/                              # 회원: Account(엔티티), AccountService(가입·인증번호·구글 연동),
│                                      # AuthController(/api/auth/**), GoogleLoginHandler, AdminAccountInitializer,
│                                      # VerificationMailer(메일, 미설정 시 로그 출력), LoginAttemptLimiter, AuthPolicy(입력 규칙)
├── config/
│   ├── LabProperties.java             # lab.* 설정 (실습 서버 주소, 제한 값)
│   ├── LabIds.java                    # labId(UUID) 검증
│   └── SecurityConfig.java            # 접근 규칙, 세션·CSRF, /api/** CORS, 구글 로그인
├── docs/
│   ├── DocsProperties.java            # docs.root (문서 폴더)
│   └── DocsController.java            # 학습 문서 API (/api/docs/**), 경로 검사
├── docker/
│   ├── DockerConfig.java              # 원격 Docker 호스트 DockerClient 설정
│   ├── DockerController.java          # Docker 연결 확인 API
│   ├── DockerExecs.java               # 컨테이너 안에서 짧은 명령 실행
│   └── LabContainerSetup.java         # 셸 연결 전 root 로 실행: 이미지 초기화(lab-init), apt 설치 차단
├── ebpf/
│   ├── EbpfLogService.java            # eBPF 에이전트 스트림을 SSE로 중계
│   └── LogController.java             # 로그 구독 API
├── capture/
│   ├── CaptureWebSocketHandler.java   # 컨테이너 안 tcpdump 실행, 패킷 전송 (/ws/capture)
│   └── PcapStreamParser.java          # pcap 스트림을 패킷 단위로 나눔
└── websocket/
    ├── WebSocketConfig.java           # WebSocket 엔드포인트 등록
    └── TerminalWebSocketHandler.java  # 컨테이너 생성, 쉘 연결, 종료 시 정리

docs/                                  # 학습 문서 Markdown (git 제외, docs/{section}/{topic}/{slug}.md)

deploy/                                # 서버에 직접 적용하는 설정 (deploy/README.md)
├── lab-server/                        # 실습 컨테이너 방화벽 스크립트, systemd 서비스
├── db/                                # 회원 DB(PostgreSQL) 컨테이너 실행 스크립트
├── lab-image/                         # 실습 컨테이너 이미지 Dockerfile
├── docker-tls/                        # Docker API TLS 인증서 생성, dockerd 설정
└── nginx/                             # 배포용 Nginx 설정 예시

src/main/resources/
├── application.properties             # lab.*, auth.*, DB·세션·메일 기본 설정
├── db/migration/                      # Flyway 테이블 정의 (V1__accounts.sql)
└── lab/block-package-install.sh       # 컨테이너 안에서 실행하는 패키지 설치 차단 스크립트 (LF 줄바꿈 유지)
```

`IseLm1Application` 은 루트 패키지(`iselm1`)에 있어야 합니다. Spring은 이 클래스가 있는 패키지와 그 하위 패키지만 스캔하므로, 새 클래스도 `iselm1` 하위에 추가합니다.

## 프론트엔드 연동

- 프론트엔드는 세션 쿠키를 보내야 하므로 `fetch(..., { credentials: 'include' })` 로 요청합니다. CORS 는 `lab.cors.allowed-origins` 의 Origin 만 허용하며 `allowCredentials` 를 켭니다.
- 구글 로그인이 끝나면 `auth.frontend-url`(기본 `http://localhost:5173`)의 화면으로 돌아갑니다.
- 프론트엔드는 `VITE_API_BASE_URL` 로 이 서버의 주소를 지정합니다. 지정하지 않으면 `http://localhost:8080` 을 사용합니다.
- WebSocket(`/ws/terminal`, `/ws/capture`)과 SSE(`/api/logs`)는 `lab.cors.allowed-origins` 에 지정한 Origin만 허용합니다. 기본값은 개발 서버(`http://localhost:5173`)입니다. 다른 Origin에서 접속하면 403으로 거부합니다.

## 배포 시 참고

- `lab.cors.allowed-origins`, `auth.frontend-url` 을 실제 프론트엔드 도메인으로 지정합니다. 구글 OAuth 클라이언트에 `https://<도메인>/login/oauth2/code/google` 리디렉션 URI 를 추가합니다.
- 학습 문서 폴더(`docs/`)는 git 에 없으므로 서버에 따로 복사하고, 필요하면 `docs.root` 로 위치를 지정합니다.
- 자동 테스트(`./gradlew test`)는 메모리 DB(H2, 테스트 전용)를 쓰므로 실제 DB 에 접속하지 않습니다.
- 서버에 직접 적용하는 설정과 절차는 [`deploy/README.md`](deploy/README.md) 에 있습니다.
- **자동 배포**: `main` 에 push 하면 1분 안에 서버가 받아서 테스트·빌드·배포합니다(백엔드는 재시작되어 진행 중인 실습이 끊김). 학습 문서는 git 에 없으므로 `deploy/upload-docs.sh` 로 올립니다 (`deploy/README.md` "자동 배포").

| 항목 | 내용 | 파일 |
|---|---|---|
| 실습 컨테이너 방화벽 | 메타데이터(`169.254.0.0/16`)·호스트·다른 컨테이너·사설망 접근 차단, 인터넷은 허용. systemd 로 영구 적용 | `deploy/lab-server/` |
| Docker API TLS | 인증 없는 `2375` 대신 TLS 클라이언트 인증(`2376`). 인증서 생성 스크립트와 dockerd 설정 | `deploy/docker-tls/` |
| Nginx + HTTPS | 프론트·백엔드를 한 도메인으로, WebSocket 업그레이드·긴 타임아웃, SSE 버퍼링 끄기 | `deploy/nginx/` |

- **Docker API 보안**: 인증 없는 `2375` 포트를 열어 두면, 그 포트에 접근할 수 있는 누구나 실습 서버의 root 권한을 얻을 수 있습니다. `deploy/docker-tls/` 로 TLS(`2376`)를 설정하고 `lab.docker.tls-verify=true`, `lab.docker.cert-path` 를 지정합니다.
- **패키지 설치 차단의 한계**: 실습 사용자는 `sudo` 로 root 가 될 수 있고 컨테이너는 인터넷에 접근할 수 있습니다. `apt` 와 다운로드 방법을 지워 apt 설치는 막지만, root 가 `curl` 등으로 파일을 직접 받아 실행하는 것까지 막지는 못합니다. 인터넷을 쓰는 실습 도구(nmap 등)를 유지하는 한 다운로드 자체는 막을 수 없습니다.
- 리버스 프록시 뒤에 둘 경우 WebSocket(`/ws/`) 업그레이드 헤더를 전달하고 읽기 제한을 실습 세션보다 길게, SSE(`/api/logs`)는 버퍼링하지 않도록 설정합니다 (`deploy/nginx/iselm1.conf.example`).
- 현재 사용자 인증은 없습니다. 허용 Origin 검사는 브라우저 요청만 막을 수 있으므로, 공개 배포 시에는 로그인 등 인증을 추가하는 것을 권장합니다.
