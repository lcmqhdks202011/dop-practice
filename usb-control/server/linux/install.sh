#!/usr/bin/env bash
# 리눅스 서버에 관리 서버 설치 (Ubuntu/Debian, Rocky/AlmaLinux/RHEL 계열)
#   sudo bash install.sh              # 8080 포트
#   sudo bash install.sh --port 9090
# 이 폴더의 usb-control-server.jar 를 설치하고 systemd 서비스로 등록합니다. 컴퓨터가 켜지면 자동으로 실행됩니다.
#   프로그램:  /opt/usb-control-server
#   데이터:    /var/lib/usb-control-server (data, backup, logs) - 이 폴더를 가끔 다른 곳에 복사해 두세요.
# 새 버전으로 올릴 때도 새 폴더에서 다시 실행하면 됩니다. 데이터는 그대로 남습니다.
set -euo pipefail

APP_DIR=/opt/usb-control-server
DATA_DIR=/var/lib/usb-control-server
SERVICE=usb-control-server
UNIT_FILE=/etc/systemd/system/$SERVICE.service
RUN_USER=usbcontrol
# 사내망에서만 접속 허용
PRIVATE_NETS="10.0.0.0/8 172.16.0.0/12 192.168.0.0/16"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$HERE/usb-control-server.jar"
PORT=8080

fail() { echo "오류: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="${2:-}"; shift 2 ;;
        --port=*) PORT="${1#*=}"; shift ;;
        -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
        *) fail "알 수 없는 옵션: $1" ;;
    esac
done
[[ "$PORT" =~ ^[0-9]+$ ]] && [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || fail "포트 번호가 잘못되었습니다: $PORT"
[ "$(id -u)" -eq 0 ] || fail "관리자 권한이 필요합니다: sudo bash $0"
[ -f "$JAR" ] || fail "usb-control-server.jar 가 이 폴더에 없습니다: $HERE"
command -v systemctl >/dev/null || fail "systemd 가 없는 시스템은 지원하지 않습니다."

# ---------------------------------------------------------------- 자바 21 이상

java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1; }

# 자바 업데이트 때 경로가 바뀌지 않도록 버전 번호가 들어간 폴더보다 고정된 이름을 먼저 찾습니다.
find_java() {
    local j v
    for j in "$(command -v java 2>/dev/null || true)" \
             /usr/lib/jvm/jre-21/bin/java /usr/lib/jvm/jre-21-openjdk/bin/java /usr/lib/jvm/java-21-openjdk/bin/java \
             /usr/lib/jvm/java-21-openjdk-amd64/bin/java /usr/lib/jvm/java-21-openjdk-arm64/bin/java \
             /usr/lib/jvm/*/bin/java; do
        [ -n "$j" ] && [ -x "$j" ] || continue
        v="$(java_major "$j")"
        if [ -n "$v" ] && [ "$v" -ge 21 ]; then echo "$j"; return 0; fi
    done
    return 1
}

JAVA="$(find_java || true)"
if [ -z "$JAVA" ]; then
    echo "자바 21을 설치합니다..."
    if command -v apt-get >/dev/null; then
        apt-get update -q
        DEBIAN_FRONTEND=noninteractive apt-get install -y -q openjdk-21-jre-headless \
            || fail "openjdk-21-jre-headless 를 설치하지 못했습니다. 자바 21 이상(예: Eclipse Temurin 21)을 직접 설치한 뒤 다시 실행하세요."
    elif command -v dnf >/dev/null; then
        dnf install -y -q java-21-openjdk-headless || fail "java-21-openjdk-headless 를 설치하지 못했습니다."
    elif command -v yum >/dev/null; then
        yum install -y -q java-21-openjdk-headless || fail "java-21-openjdk-headless 를 설치하지 못했습니다."
    else
        fail "자바 21 이상을 직접 설치한 뒤 다시 실행하세요."
    fi
    JAVA="$(find_java || true)"
    [ -n "$JAVA" ] || fail "자바 21 이상을 찾을 수 없습니다."
fi
echo "자바: $JAVA ($(java_major "$JAVA"))"

# ---------------------------------------------------------------- 방화벽

firewall_allow() {
    local n
    if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
        for n in $PRIVATE_NETS; do ufw allow from "$n" to any port "$1" proto tcp comment "$SERVICE" >/dev/null; done
        echo "방화벽(ufw): 사내망에서 $1 포트 허용"
    elif command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
        for n in $PRIVATE_NETS; do
            firewall-cmd --permanent --add-rich-rule="rule family=\"ipv4\" source address=\"$n\" port port=\"$1\" protocol=\"tcp\" accept" >/dev/null
        done
        firewall-cmd --reload >/dev/null
        echo "방화벽(firewalld): 사내망에서 $1 포트 허용"
    else
        echo "켜져 있는 방화벽(ufw, firewalld)이 없습니다. 다른 방화벽을 쓰면 사내망에서 $1 포트를 직접 열어 주세요."
    fi
}

firewall_remove() {
    local n
    if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
        for n in $PRIVATE_NETS; do ufw delete allow from "$n" to any port "$1" proto tcp >/dev/null 2>&1 || true; done
    elif command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
        for n in $PRIVATE_NETS; do
            firewall-cmd --permanent --remove-rich-rule="rule family=\"ipv4\" source address=\"$n\" port port=\"$1\" protocol=\"tcp\" accept" >/dev/null 2>&1 || true
        done
        firewall-cmd --reload >/dev/null
    fi
}

# ---------------------------------------------------------------- 설치

# 다시 설치(업데이트)할 때: 서버를 멈추고, 포트가 바뀌었으면 예전 방화벽 규칙을 지웁니다.
OLD_PORT=""
if [ -f "$UNIT_FILE" ]; then
    OLD_PORT="$(sed -n 's/.*--server\.port=\([0-9]*\).*/\1/p' "$UNIT_FILE" | head -n 1)"
    systemctl stop "$SERVICE" || true
fi
if [ -n "$OLD_PORT" ] && [ "$OLD_PORT" != "$PORT" ]; then firewall_remove "$OLD_PORT"; fi

# 서버는 로그인할 수 없는 전용 계정으로 실행합니다.
if ! id "$RUN_USER" >/dev/null 2>&1; then
    useradd --system --home-dir "$DATA_DIR" --no-create-home --shell /usr/sbin/nologin "$RUN_USER"
fi

install -d -m 755 "$APP_DIR"
install -m 644 "$JAR" "$APP_DIR/usb-control-server.jar"
install -m 755 "$HERE/uninstall.sh" "$APP_DIR/uninstall.sh" 2>/dev/null || true
# 데이터베이스와 백업이 들어가므로 서버 계정만 접근
install -d -m 750 -o "$RUN_USER" -g "$RUN_USER" "$DATA_DIR"
chown -R "$RUN_USER:$RUN_USER" "$DATA_DIR"

# 1024 아래 포트는 일반 계정이 열 수 없어 권한을 따로 줍니다.
CAPS=""
if [ "$PORT" -lt 1024 ]; then CAPS="AmbientCapabilities=CAP_NET_BIND_SERVICE"; fi

cat > "$UNIT_FILE" <<EOF
[Unit]
Description=USB 매체제어 관리 서버
After=network-online.target
Wants=network-online.target

[Service]
User=$RUN_USER
Group=$RUN_USER
WorkingDirectory=$DATA_DIR
ExecStart=$JAVA -jar $APP_DIR/usb-control-server.jar --server.port=$PORT
SuccessExitStatus=143
Restart=always
RestartSec=10
UMask=0027
$CAPS
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=$DATA_DIR

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --quiet "$SERVICE"
systemctl restart "$SERVICE"
firewall_allow "$PORT"

# ---------------------------------------------------------------- 시작 확인

echo "서버를 시작하는 중..."
ok=""
for _ in $(seq 1 60); do
    sleep 2
    if command -v curl >/dev/null; then
        curl -fsS -o /dev/null "http://localhost:$PORT/login" 2>/dev/null && { ok=1; break; }
    elif command -v wget >/dev/null; then
        wget -q -O /dev/null "http://localhost:$PORT/login" 2>/dev/null && { ok=1; break; }
    else
        systemctl is-active --quiet "$SERVICE" && { ok=1; break; }
    fi
done
if [ -z "$ok" ]; then
    echo "서버가 2분 안에 뜨지 않았습니다. 'journalctl -u $SERVICE' 와 $DATA_DIR/logs/server.log 를 확인하세요." >&2
    exit 1
fi

echo
echo "설치를 마쳤습니다."
echo "  관리 화면: http://localhost:$PORT (다른 PC에서는 아래 주소)"
for ip in $(hostname -I 2>/dev/null || true); do
    case "$ip" in *:*|127.*) ;; *) echo "             http://$ip:$PORT" ;; esac
done
echo "  데이터 폴더: $DATA_DIR/data, 백업 폴더: $DATA_DIR/backup"
echo "  상태 보기: systemctl status $SERVICE   /   제거: sudo bash $APP_DIR/uninstall.sh"
