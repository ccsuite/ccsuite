@echo off
rem Builds the taskbar slot DLL (llvm-mingw clang, size-optimised) and puts it where the jar picks it up as a resource.
rem Any argument is the output file instead, and then the DLL reads the scratch registry keys Software\ccsuite\taskbar-test\{legacy,cards} (for hosttest.c), never the real ones.
setlocal
set OUT=%~dp0..\src\main\resources\ccsuite\taskbarslot\ccuslot.dll
set DEFS=
if not "%~1"=="" (
  set OUT=%~1
  set DEFS=-DLEGACY_KEY=L\"Software\\ccsuite\\taskbar-test\\legacy\" -DCARDS_KEY=L\"Software\\ccsuite\\taskbar-test\\cards\"
) else if not exist "%~dp0..\src\main\resources\ccsuite\taskbarslot" mkdir "%~dp0..\src\main\resources\ccsuite\taskbarslot"
gcc -shared -Os -s -ffunction-sections -fdata-sections -fno-unwind-tables -fno-asynchronous-unwind-tables -Wall -Wextra -Wno-unused-parameter %DEFS% -Wl,--gc-sections -Wl,--strip-all -o "%OUT%" "%~dp0slot.c" "%~dp0slot.def" -lole32 -luuid -luser32 -lgdi32 -ladvapi32
if errorlevel 1 exit /b 1
for %%F in ("%OUT%") do echo built %%~fF  %%~zF bytes
