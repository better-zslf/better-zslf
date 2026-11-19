@echo off
REM 完全脱离 DSH 进程树的重启脚本。
REM 关键：脚本自身不能是 DSH 的子进程，否则 Stop-Process 会连自己一起带走。
timeout /t 2 /nobreak >nul
taskkill /IM "DeepSeek Harness.exe" /F >nul 2>&1
timeout /t 4 /nobreak >nul
start "" "C:\Users\bx233\AppData\Local\Programs\DeepSeek Harness\DeepSeek Harness.exe"
