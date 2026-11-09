@echo off
REM fclaude monitor: start hidden and detached from any terminal or Claude session. One copy only. Read-only checks every 10 minutes.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fclaude-monitor-start.ps1"
