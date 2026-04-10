@echo off
REM CR-042: Windows service install script
REM Requires: AimbaseAgentService.exe (WinSW) in the same directory

set INSTALL_DIR=%~dp0

echo ================================================================
echo  Aimbase Agent Service Installer
echo ================================================================

REM Create default config if not exists
if not exist "%USERPROFILE%\.aimbase-agent\config" mkdir "%USERPROFILE%\.aimbase-agent\config"
if not exist "%USERPROFILE%\.aimbase-agent\config\application.yml" (
    copy "%INSTALL_DIR%application.yml.default" "%USERPROFILE%\.aimbase-agent\config\application.yml"
    echo.
    echo  SETUP REQUIRED: Edit configuration before starting:
    echo    %USERPROFILE%\.aimbase-agent\config\application.yml
    echo.
    echo  Set these required fields:
    echo    agent.aimbase-url: http://your-aimbase-server:8181
    echo    agent.api-key: your-api-key
    echo.
)

REM Create workspace directory
if not exist "%USERPROFILE%\aimbase-workspace" mkdir "%USERPROFILE%\aimbase-workspace"

REM Install and start service
"%INSTALL_DIR%AimbaseAgentService.exe" install
"%INSTALL_DIR%AimbaseAgentService.exe" start

echo.
echo  Service installed and started.
echo  Logs: %USERPROFILE%\.aimbase-agent\logs\agent.log
echo ================================================================
pause
