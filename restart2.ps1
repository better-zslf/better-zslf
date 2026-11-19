$exe = "C:\Users\bx233\AppData\Local\Programs\DeepSeek Harness\DeepSeek Harness.exe"
Get-Process -Name "DeepSeek Harness" -ErrorAction SilentlyContinue |
  Where-Object { $_.MainWindowTitle -ne "" } |
  ForEach-Object { "before: PID $($_.Id)"; $_.CloseMainWindow() | Out-Null }
Start-Sleep -Seconds 6
$left = Get-Process -Name "DeepSeek Harness" -ErrorAction SilentlyContinue
if ($left) { $left | ForEach-Object { "kill: PID $($_.Id)" }; $left | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 3 }
Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe)
"relaunched"
