#!/bin/bash

SERVICE_FILE="scamusica.service"
USER_SYSTEMD_DIR="$HOME/.config/systemd/user"

echo "Setting up Scamusica systemd service..."

if [ ! -f "$SERVICE_FILE" ]; then
    echo "Error: $SERVICE_FILE not found in current directory."
    exit 1
fi

mkdir -p "$USER_SYSTEMD_DIR"
cp "$SERVICE_FILE" "$USER_SYSTEMD_DIR/"

systemctl --user daemon-reload
systemctl --user enable scamusica.service
systemctl --user start scamusica.service

# Enable lingering so service runs even when not logged in (optional depending on setup)
loginctl enable-linger $USER

echo "Scamusica service setup complete. Check status with: systemctl --user status scamusica.service"
