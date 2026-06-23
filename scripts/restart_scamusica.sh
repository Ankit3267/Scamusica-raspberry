#!/bin/bash

# Configuration
LOG_FILE="$HOME/.scamusica/logs/restart.log"
mkdir -p "$(dirname "$LOG_FILE")"

echo "[$(date)] Restart script initiated..." >> "$LOG_FILE"

# 1. Wait for parent JVM to exit cleanly
sleep 3

# 2. Kill any remaining Scamusica processes
echo "[$(date)] Killing straggler processes..." >> "$LOG_FILE"
pkill -f 'Scamusica'
pkill -f 'scamusica.jar'

# 3. Small wait to ensure ports and handles are released
sleep 2

# 4. Try dropping caches if we have sudo without password
if sudo -n true 2>/dev/null; then
    echo "[$(date)] Dropping OS caches..." >> "$LOG_FILE"
    sync
    sudo sh -c 'echo 3 > /proc/sys/vm/drop_caches'
fi

# 5. Set DISPLAY variable so GUI can launch
export DISPLAY=:0

# 6. Relaunch
echo "[$(date)] Relaunching application..." >> "$LOG_FILE"

if [ -x "/opt/scamusica/bin/Scamusica" ]; then
    nohup /opt/scamusica/bin/Scamusica > /dev/null 2>&1 &
elif [ -f "$HOME/scamusica/scamusica.jar" ]; then
    nohup java -jar "$HOME/scamusica/scamusica.jar" > /dev/null 2>&1 &
else
    echo "[$(date)] ERROR: Could not find Scamusica executable or jar to relaunch." >> "$LOG_FILE"
    exit 1
fi

echo "[$(date)] Restart completed successfully." >> "$LOG_FILE"
