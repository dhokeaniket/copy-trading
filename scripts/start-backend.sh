#!/bin/bash
# Single-instance backend start for EC2. Sources Telegram env from ec2-user .bashrc.
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ec2-user/copy-trading}"
JAR="${APP_DIR}/build/libs/copy-trading-backend-0.1.0.jar"
LOG="${LOG:-/home/ec2-user/ascentra.log}"
PORT=8081

# Load TELEGRAM_* and other exports
if [[ -f /home/ec2-user/.bashrc ]]; then
  # shellcheck source=/dev/null
  source /home/ec2-user/.bashrc
fi

cd "$APP_DIR"

echo "Stopping anything on port ${PORT}..."
if command -v fuser >/dev/null 2>&1; then
  sudo fuser -k "${PORT}/tcp" 2>/dev/null || true
fi
killall java 2>/dev/null || true
sleep 5

if ss -tlnp 2>/dev/null | grep -q ":${PORT} "; then
  echo "ERROR: port ${PORT} still in use:"
  ss -tlnp | grep ":${PORT} " || true
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: JAR not found: $JAR — run ./gradlew clean build -x test first"
  exit 1
fi

echo "Starting backend (TELEGRAM_ENABLED=${TELEGRAM_ENABLED:-false})..."
nohup java -Xmx512m -jar "$JAR" >> "$LOG" 2>&1 &
echo "PID: $!"
echo "Wait ~45s then: tail -30 $LOG | grep -iE 'Started|TELEGRAM|FAILED|8081'"
