# ISE-LM1 Backend

정보보안기사와 리눅스마스터 1급 시험을 준비하기 위한 학습 사이트의 백엔드입니다.
프론트엔드([ISE-LM1-Frontend](https://github.com/ksm008/ISE-LM1-Frontend))의 터미널 실습에 필요한 실습용 리눅스 컨테이너를 만들고, 브라우저와 컨테이너 쉘을 연결하며, eBPF 모니터링 로그를 실시간으로 전달합니다.

## 주요 기능

- **터미널 실습 (WebSocket)**
  - 실습을 시작하면 원격 Docker 호스트에 실습용 컨테이너(`sec-lab-ubuntu:1.0`)를 새로 생성합니다.
  - 컨테이너의 `/bin/bash` 쉘을 WebSocket으로 연결해 브라우저에서 바로 사용할 수 있습니다.
  - WebSocket 연결이 종료되면 컨테이너를 자동으로 삭제합니다.
- **eBPF 모니터링 로그 (SSE)**
  - 실습 서버의 eBPF 에이전트가 수집한 컨테이너 로그를 Server-Sent Events로 실시간 전달합니다.
  - 실습이 종료되면 로그 스트림도 함께 종료합니다.
- **Docker 연결 확인**: 컨테이너를 만들지 않고 원격 Docker 호스트 연결 상태만 확인하는 API를 제공합니다.
- **실습 자원 보호**
  - 동시 실습 수, 컨테이너 메모리·CPU·프로세스 수를 제한합니다.
  - 입력 없이 일정 시간이 지나면 터미널 연결을 끊고 컨테이너를 삭제합니다.
  - 서버 종료 시, 그리고 비정상 종료 후 다시 시작할 때 남은 실습 컨테이너를 정리합니다.

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
| `lab.container.image` | `sec-lab-ubuntu:1.0` | 실습 컨테이너 이미지 |
| `lab.container.memory-mb` | `512` | 컨테이너 메모리 제한 (스왑 없음) |
| `lab.container.cpus` | `1.0` | 컨테이너 CPU 제한 |
| `lab.container.pids-limit` | `256` | 컨테이너 프로세스 수 제한 |
| `lab.session.max-sessions` | `10` | 동시 실습 수 |
| `lab.session.idle-timeout` | `30m` | 입력이 없을 때 터미널 연결을 끊는 시간 |
| `lab.session.sse-timeout` | `30m` | SSE 연결 유지 시간 (만료 시 브라우저가 자동 재연결) |

- 실습 서버에 `lab.container.image` 이미지가 있어야 합니다. 없으면 첫 실습을 시작할 때 이미지 다운로드를 시도합니다.
- 이 서버가 만든 컨테이너에는 `iselm1.lab=true` 라벨이 붙으며, 서버 시작 시 이 라벨이 붙은 컨테이너는 정리됩니다. 같은 Docker 호스트를 여러 백엔드 인스턴스가 함께 쓰지 않도록 합니다.

## API

| 종류 | 엔드포인트 | 설명 |
|---|---|---|
| WebSocket | `/ws/terminal?labId={labId}` | 실습 컨테이너의 쉘 입출력 |
| SSE | `/api/logs?labId={labId}` | 해당 실습의 eBPF 모니터링 로그 스트림 (`text/event-stream`) |
| GET | `/api/docker/health` | Docker 연결 상태 (`{"status":"UP"}`, 실패 시 503 `DOWN`) |

- `labId` 는 프론트엔드가 실습을 시작할 때 UUID로 생성해 두 연결에 같은 값을 사용합니다. UUID 형식이 아니면 거부합니다(SSE는 400, WebSocket은 1008로 종료).
- 이미 실습 중인 `labId` 로 다시 WebSocket을 연결하면 거부합니다.
- 동시 실습 수를 넘으면 SSE는 503, WebSocket은 1013(`동시 실습 수 초과`)으로 종료합니다.
- 오류가 나면 내부 오류 내용은 클라이언트에 보내지 않고 서버 로그에만 남깁니다.
- SSE는 구독 직후 `:connected` comment를 보내 연결을 바로 엽니다 (브라우저 `EventSource` 는 comment를 무시합니다).

### WebSocket 메시지

클라이언트 → 서버 메시지는 JSON입니다. 형식이 맞지 않는 메시지는 무시합니다. 서버 → 클라이언트는 쉘 출력 텍스트 그대로입니다.

| type | 예시 | 설명 |
|---|---|---|
| `input` | `{"type":"input","data":"ls\r"}` | 쉘 입력 |
| `resize` | `{"type":"resize","cols":120,"rows":30}` | 컨테이너 TTY 크기 변경 (1~500) |

### 실습 흐름

1. 프론트엔드가 `/api/logs?labId={labId}` 로 SSE 구독을 엽니다.
2. SSE가 열리면 같은 `labId` 로 `/ws/terminal?labId={labId}` 에 WebSocket을 연결합니다 (SSE 구독 전에 나온 로그는 전달되지 않으므로 순서를 지킵니다).
3. 백엔드가 실습 컨테이너를 생성하고, eBPF 에이전트에 해당 컨테이너의 로그 스트림을 요청한 뒤 쉘을 연결합니다.
4. 쉘 출력은 WebSocket으로, eBPF 로그는 SSE로 전달됩니다.
5. WebSocket 연결이 종료되면 컨테이너를 삭제하고 SSE 연결도 종료합니다.

## 프로젝트 구조

```
src/main/java/iselm1/
├── IseLm1Application.java             # 엔트리
├── config/
│   ├── LabProperties.java             # lab.* 설정 (실습 서버 주소, 제한 값)
│   ├── LabIds.java                    # labId(UUID) 검증
│   └── WebConfig.java                 # /api/** CORS 설정
├── docker/
│   ├── DockerConfig.java              # 원격 Docker 호스트 DockerClient 설정
│   └── DockerController.java          # Docker 연결 확인 API
├── ebpf/
│   ├── EbpfLogService.java            # eBPF 에이전트 스트림을 SSE로 중계
│   └── LogController.java             # 로그 구독 API
└── websocket/
    ├── WebSocketConfig.java           # WebSocket 엔드포인트 등록
    └── TerminalWebSocketHandler.java  # 컨테이너 생성, 쉘 연결, 종료 시 정리
```

`IseLm1Application` 은 루트 패키지(`iselm1`)에 있어야 합니다. Spring은 이 클래스가 있는 패키지와 그 하위 패키지만 스캔하므로, 새 클래스도 `iselm1` 하위에 추가합니다.

## 프론트엔드 연동

- 프론트엔드는 `VITE_API_BASE_URL` 로 이 서버의 주소를 지정합니다. 지정하지 않으면 `http://localhost:8080` 을 사용합니다.
- WebSocket(`/ws/terminal`)과 SSE(`/api/logs`)는 `lab.cors.allowed-origins` 에 지정한 Origin만 허용합니다. 기본값은 개발 서버(`http://localhost:5173`)입니다. 다른 Origin에서 접속하면 403으로 거부합니다.

## 배포 시 참고

- `lab.cors.allowed-origins` 를 실제 프론트엔드 도메인으로 지정합니다.
- **Docker API 보안**: 인증 없는 `2375` 포트를 열어 두면, 그 포트에 접근할 수 있는 누구나 실습 서버의 root 권한을 얻을 수 있습니다. 실습 서버의 Docker에 TLS(`2376`, `--tlsverify`)를 설정하고 `lab.docker.tls-verify=true`, `lab.docker.cert-path` 를 지정하는 것을 권장합니다. 최소한 방화벽으로 백엔드 서버에서만 접근할 수 있게 제한합니다.
- 리버스 프록시(Nginx 등) 뒤에 둘 경우 WebSocket 업그레이드 헤더를 전달하고, SSE 응답이 버퍼링되지 않도록 설정해야 합니다.
- 현재 사용자 인증은 없습니다. 허용 Origin 검사는 브라우저 요청만 막을 수 있으므로, 공개 배포 시에는 로그인 등 인증을 추가하는 것을 권장합니다.
