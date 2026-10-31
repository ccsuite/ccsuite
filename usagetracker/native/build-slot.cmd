@echo off
rem Builds the taskbar slot DLL (llvm-mingw clang, size-optimised) and puts it where the jar picks it up as a resource.
setlocal
set OUT=%~dp0..\src\main\resources\ccsuite\usagetracker\taskbar\ccuslot.dll
if not exist "%~dp0..\src\main\resources\ccsuite\usagetracker\taskbar" mkdir "%~dp0..\src\main\resources\ccsuite\usagetracker\taskbar"
gcc -shared -Os -s -ffunction-sections -fdata-sections -fno-unwind-tables -fno-asynchronous-unwind-tables -Wall -Wl,--gc-sections -Wl,--strip-all -o "%OUT%" "%~dp0slot.c" "%~dp0slot.def" -lole32 -luuid -luser32 -lgdi32 -ladvapi32
if errorlevel 1 exit /b 1
for %%F in ("%OUT%") do echo built %%~fF  %%~zF bytes
