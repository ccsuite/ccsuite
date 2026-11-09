@echo off
if not exist "%USERPROFILE%\ccsuite\ccacc\monitor" mkdir "%USERPROFILE%\ccsuite\ccacc\monitor"
echo stop> "%USERPROFILE%\ccsuite\ccacc\monitor\monitor.stop"
echo stop requested; the monitor exits within 15 seconds
