#!/bin/bash
cd /opt/scamusica/lib/app

# Dynamically resolve JAR filename to avoid Java wildcard limitations in classpath
JAR_FILES=(Scamusica-*.jar)
JAR_FILE="${JAR_FILES[0]}"

exec /opt/scamusica/lib/runtime/bin/java \
    -Xmx512m -Xms256m \
    -Djna.library.path=/opt/scamusica/lib/app/lib/vlc \
    -DVLC_PLUGIN_PATH=/opt/scamusica/lib/app/lib/vlc/plugins \
    -cp "$JAR_FILE" \
    com.musicplayer.scamusica.Main "$@"