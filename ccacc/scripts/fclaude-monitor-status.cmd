@echo off
type "%USERPROFILE%\ccsuite\ccacc\monitor\status.md"
if exist "%USERPROFILE%\ccsuite\ccacc\monitor\ATTENTION.txt" (echo. & echo ATTENTION: & type "%USERPROFILE%\ccsuite\ccacc\monitor\ATTENTION.txt")
