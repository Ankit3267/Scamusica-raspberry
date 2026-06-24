#!/bin/bash

LOG_FILE="$HOME/.scamusica/logs/app.log"
RESTART_SCRIPT="/opt/scamusica/lib/app/restart_scamusica.sh"
APP_BINARY="/opt/scamusica/bin/Scamusica"

mkdir -p "$(dirname "$LOG_FILE")"

echo "[$(date)] ✅ Wrapper started. Monitoring for JNA errors..." >> "$LOG_FILE"

# App run karo - stdout aur stderr dono capture karo
"$APP_BINARY" 2>&1 | while IFS= read -r line; do

    # Har line log mein likho
    echo "[$(date)] $line" >> "$LOG_FILE"

    # JNA error detect karo
    if echo "$line" | grep -qi "JNA\|failed to create structure\|error handling callback"; then
        echo "[$(date)] ⚠️ JNA ERROR DETECTED: $line" >> "$LOG_FILE"
        echo "[$(date)] 🔄 Triggering restart..." >> "$LOG_FILE"
        bash "$RESTART_SCRIPT" &
        sleep 5
        exit 1
    fi

done

echo "[$(date)] ⚠️ App exited unexpectedly. Wrapper ending." >> "$LOG_FILE"