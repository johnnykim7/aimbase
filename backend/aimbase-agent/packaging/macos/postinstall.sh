#!/bin/bash
# CR-042: macOS post-install script
# Copies default config and installs launchd service

set -e

CONFIG_DIR="$HOME/.aimbase-agent/config"
PLIST_SRC="/Applications/AimbaseAgent.app/Contents/Resources/com.platform.aimbase-agent.plist"
PLIST_DST="$HOME/Library/LaunchAgents/com.platform.aimbase-agent.plist"

# Create default config if not exists
mkdir -p "$CONFIG_DIR"
if [ ! -f "$CONFIG_DIR/application.yml" ]; then
    cp "/Applications/AimbaseAgent.app/Contents/Resources/application.yml.default" "$CONFIG_DIR/application.yml"
    echo "================================================================"
    echo " Aimbase Agent installed successfully!"
    echo ""
    echo " SETUP REQUIRED: Edit configuration before starting:"
    echo "   $CONFIG_DIR/application.yml"
    echo ""
    echo " Set these required fields:"
    echo "   agent.aimbase-url: http://your-aimbase-server:8181"
    echo "   agent.api-key: your-api-key"
    echo "================================================================"
fi

# Create workspace directory
mkdir -p "$HOME/aimbase-workspace"

# Install launchd plist (user-level, no root required)
mkdir -p "$HOME/Library/LaunchAgents"
cp "$PLIST_SRC" "$PLIST_DST"

# Load the service (will start on next login if RunAtLoad=true)
launchctl load "$PLIST_DST" 2>/dev/null || true

echo "Aimbase Agent service registered. It will start automatically on login."
echo "To start now: launchctl start com.platform.aimbase-agent"
echo "To stop:      launchctl stop com.platform.aimbase-agent"
echo "Logs:         ~/.aimbase-agent/logs/agent.log"
