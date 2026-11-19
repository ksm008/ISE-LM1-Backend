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
- **Docker 연결 확인**: 원격 Docker 호스트 연결을 확인하기 위한 테스트용 API를 제공합니다.

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

### 실습 서버

이 서버는 원격 실습 서버(Tailscale 네트워크)에 아래 두 가지가 실행 중이어야 동작합니다.

| 대상 | 주소 | 설정 위치 |
|---|---|---|
| Docker Engine API | `tcp://100.100.215.116:2375` | `docker/DockerConfig.java` |
| eBPF 로그 에이전트 | `http://100.100.215.116:9000/stream` | `ebpf/EbpfLogService.java` |

- 실습 서버에 `sec-lab-ubuntu:1.0` 이미지가 있어야 합니다. 없으면 첫 실습을 시작할 때 이미지 다운로드를 시도합니다.
- 실습 서버 주소를 바꾸려면 위 설정 위치의 값을 수정합니다.

## API

| 종류 | 엔드포인트 | 설명 |
|---|---|---|
| WebSocket | `/ws/terminal?labId={labId}` | 실습 컨테이너의 쉘 입출력 |
| SSE | `/api/logs?labId={labId}` | 해당 실습의 eBPF 모니터링 로그 스트림 (`text/event-stream`) |
| GET | `/start-container` | Docker 연결 확인용 테스트 컨테이너(`arm64v8/alpine`) 실행 |

- `labId` 는 프론트엔드가 실습을 시작할 때 UUID로 생성해 두 연결에 같은 값을 사용합니다.
- WebSocket 메시지로 보낸 문자열은 그대로 쉘 입력으로 전달되고, 쉘 출력은 WebSocket 메시지로 돌아옵니다.

### 실습 흐름

1. 프론트엔드가 `/api/logs?labId={labId}` 로 SSE 구독을 엽니다.
2. 같은 `labId` 로 `/ws/terminal?labId={labId}` 에 WebSocket을 연결합니다.
3. 백엔드가 실습 컨테이너를 생성하고, eBPF 에이전트에 해당 컨테이너의 로그 스트림을 요청한 뒤 쉘을 연결합니다.
4. 쉘 출력은 WebSocket으로, eBPF 로그는 SSE로 전달됩니다.
5. WebSocket 연결이 종료되면 컨테이너를 삭제하고 SSE 연결도 종료합니다.

## 프로젝트 구조

```
src/main/java/iselm1/
├── IseLm1Application.java             # 엔트리
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
- WebSocket(`/ws/terminal`)과 SSE(`/api/logs`)는 모든 Origin을 허용하도록 설정되어 있어, 개발 서버(`http://localhost:5173`)에서 바로 연결할 수 있습니다.

## 배포 시 참고

- 현재 모든 Origin을 허용하고 있으므로, 배포할 때는 프론트엔드 도메인만 허용하도록 `WebSocketConfig` 와 `LogController` 의 설정을 바꾸는 것을 권장합니다.
- 리버스 프록시(Nginx 등) 뒤에 둘 경우 WebSocket 업그레이드 헤더를 전달하고, SSE 응답이 버퍼링되지 않도록 설정해야 합니다.
