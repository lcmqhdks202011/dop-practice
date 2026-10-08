#!/usr/bin/env bash
# 관리 서버를 멈추고 자동 실행, 방화벽 규칙, 프로그램을 지웁니다.
# 데이터(/var/lib/usb-control-server)와 서버 계정(usbcontrol)은 남겨 둡니다. 다시 설치하면 그대로 이어 씁니다.
#   sudo bash uninstall.sh
set -euo pipefail

APP_DIR=/opt/usb-control-server
DATA_DIR=/var/lib/usb-control-server
SERVICE=usb-control-server
UNIT_FILE=/etc/systemd/system/$SERVICE.service
PRIVATE_NETS="10.0.0.0/8 172.16.0.0/12 192.168.0.0/16"

[ "$(id -u)" -eq 0 ] || { echo "관리자 권한이 필요합니다: sudo bash $0" >&2; exit 1; }

PORT=""
if [ -f "$UNIT_FILE" ]; then
    PORT="$(sed -n 's/.*--server\.port=\([0-9]*\).*/\1/p' "$UNIT_FILE" | head -n 1)"
fi

systemctl disable --now "$SERVICE" >/dev/null 2>&1 || true
rm -f "$UNIT_FILE"
systemctl daemon-reload

if [ -n "$PORT" ]; then
    if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
        for n in $PRIVATE_NETS; do ufw delete allow from "$n" to any port "$PORT" proto tcp >/dev/null 2>&1 || true; done
    elif command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
        for n in $PRIVATE_NETS; do
            firewall-cmd --permanent --remove-rich-rule="rule family=\"ipv4\" source address=\"$n\" port port=\"$PORT\" protocol=\"tcp\" accept" >/dev/null 2>&1 || true
        done
        firewall-cmd --reload >/dev/null
    fi
fi

rm -rf "$APP_DIR"

echo "관리 서버를 멈추고 지웠습니다. 데이터($DATA_DIR)는 남아 있습니다."
echo "데이터까지 모두 지우려면: sudo rm -rf $DATA_DIR && sudo userdel usbcontrol"
