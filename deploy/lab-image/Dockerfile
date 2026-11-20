FROM arm64v8/ubuntu:24.04

# 설치 중 대화형 질문(tzdata 등)이 나오지 않게 한다
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
	bash \
	sudo \
	vim \
	nano \
	curl \
	wget \
	ca-certificates \
	net-tools \
	iputils-ping \
	iproute2 \
	dnsutils \
	nmap \
	tcpdump \
	netcat-openbsd \
	openssh-client \
	python3 \
	cron \
	rsyslog \
	bind9 \
	bind9-utils \
	vsftpd \
	apache2 \
	acct \
	&& rm -rf /var/lib/apt/lists/*

# 컨테이너에는 커널 로그(/proc/kmsg)가 없으므로 rsyslog 의 imklog 모듈을 끈다
RUN sed -i 's/^module(load="imklog"/# &/' /etc/rsyslog.conf
# rsyslog 패키지에는 systemd 서비스만 있으므로 service 명령용 init 스크립트를 넣는다
# (서버 Docker 는 BuildKit 이 없어 COPY --chmod 를 쓸 수 없다)
COPY rsyslog.init /etc/init.d/rsyslog
RUN chmod 0755 /etc/init.d/rsyslog
# apache2 시작 시 ServerName 경고를 없앤다
RUN echo "ServerName localhost" > /etc/apache2/conf-available/servername.conf \
	&& a2enconf servername >/dev/null

# ---- 계정·로그 보안 실습용 (Ubuntu 기본값에는 없는 파일·로그) ----
# /etc/securetty + pam_securetty: root 직접 로그인을 허용할 터미널 제한
COPY securetty /etc/securetty
RUN chmod 0600 /etc/securetty \
	&& (grep -q pam_securetty /etc/pam.d/login \
		|| sed -i '0,/^@include common-auth/s//auth       requisite  pam_securetty.so\n&/' /etc/pam.d/login) \
	&& sed -i 's/^\([^#].*pam_lastlog\.so.*\)$/# \1  # Ubuntu 24.04 에는 모듈이 없어 로그에 오류만 남는다/' /etc/pam.d/login
# /var/log/secure (authpriv), /var/log/sulog (su 사용 기록)
COPY 60-iselm1.conf /etc/rsyslog.d/60-iselm1.conf
# 컨테이너가 만들어질 때 백엔드가 실행하는 초기화 (rsyslog, 프로세스 accounting 시작)
COPY lab-init /usr/local/sbin/lab-init
RUN chmod 0644 /etc/rsyslog.d/60-iselm1.conf && chmod 0755 /usr/local/sbin/lab-init

RUN useradd -m -s /bin/bash student \
	&& echo "student:student" | chpasswd \
	&& usermod -aG sudo student \
	&& echo "student ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers \
	&& echo "root:root" | chpasswd
# root 비밀번호는 su 실습용이다 (student 는 이미 sudo 로 root 가 될 수 있으므로 권한이 늘지 않는다)

USER student
WORKDIR /home/student

# 백엔드는 이 명령을 "tail -f /dev/null" 로 덮어써서 실행한다.
# 컨테이너가 만들어지면 백엔드가 /usr/local/sbin/lab-init 을 실행해 rsyslog 와 프로세스 accounting 을 켠다.
# 나머지 서비스(cron, named, vsftpd, apache2)는 실습에서 sudo service <이름> start 로 켠다.
CMD ["tail", "-f", "/dev/null"]
