#!/bin/bash

LOG_FILE="$HOME/.scamusica/logs/restart.log"
mkdir -p "$(dirname "$LOG_FILE")"

echo "[$(date)] 🔄 Restart script initiated..." >> "$LOG_FILE"

# 1. JVM band hone do
sleep 3

# 2. Baaki processes kill karo
echo "[$(date)] Killing straggler processes..." >> "$LOG_FILE"
pkill -f 'Scamusica'
pkill -f 'scamusica.jar'
pkill -f 'scamusica_wrapper'
sleep 2

# 3. OS cache clear karo agar sudo available ho
if sudo -n true 2>/dev/null; then
    echo "[$(date)] Dropping OS caches..." >> "$LOG_FILE"
    sync
    sudo sh -c 'echo 3 > /proc/sys/vm/drop_caches'
fi

# 4. DISPLAY set karo
export DISPLAY=:0

# 5. Wrapper se relaunch karo
echo "[$(date)] Relaunching via wrapper..." >> "$LOG_FILE"

if [ -x "/opt/scamusica/lib/app/scamusica_wrapper.sh" ]; then
    nohup /opt/scamusica/lib/app/scamusica_wrapper.sh > /dev/null 2>&1 &
elif [ -x "/opt/scamusica/bin/Scamusica" ]; then
    nohup /opt/scamusica/bin/Scamusica > /dev/null 2>&1 &
else
    echo "[$(date)] ❌ ERROR: No executable found!" >> "$LOG_FILE"
    exit 1
fi

echo "[$(date)] ✅ Restart completed." >> "$LOG_FILE"