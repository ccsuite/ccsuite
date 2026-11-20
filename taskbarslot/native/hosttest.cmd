@echo off
rem Builds the slot DLL for the scratch registry keys and the test host, and runs the host against it. Touches only HKCU\Software\ccsuite\taskbar-test. Exit code 0 = every check passed.
setlocal
set WORK=%~1
if "%WORK%"=="" set WORK=%TEMP%\slot-hosttest
if not exist "%WORK%" mkdir "%WORK%"
call "%~dp0build-slot.cmd" "%WORK%\ccuslot-test.dll" || exit /b 1
gcc -O2 -Wall -Wextra -Wno-unused-parameter -o "%WORK%\hosttest.exe" "%~dp0hosttest.c" -lole32 -luuid -luser32 -lgdi32 -ladvapi32 || exit /b 1
"%WORK%\hosttest.exe" "%WORK%\ccuslot-test.dll"
exit /b %errorlevel%
