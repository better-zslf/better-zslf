#Requires -Version 7.2
[CmdletBinding()]
param(
    [ValidateRange(1536, 4096)] [int] $MemoryMiB = 1536,
    [ValidateRange(1, 4)] [int] $Cores = 2,
    [ValidateRange(5554, 5682)] [int] $Port = 5554,
    [switch] $Window,
    [switch] $SoftwareCpu
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'use-local-tools.ps1')
if ($Port % 2 -ne 0) { throw 'Android emulator console port must be even' }
$emulator = "$env:ANDROID_HOME\emulator\emulator.exe"
if (-not (Test-Path -LiteralPath $emulator)) { throw 'Run scripts/prepare-emulator.ps1 first' }
if (-not $SoftwareCpu) {
    & $emulator -accel-check
    if ($LASTEXITCODE -ne 0) { throw 'Hardware acceleration is not ready; no global features were changed. SoftwareCpu is an explicit slow fallback.' }
} else {
    Write-Warning 'Software CPU emulation selected; boot and execution will be substantially slower.'
}
$freeMiB = [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
# This API36/Emulator37 combination actually enforces a 2560MiB guest minimum.
$requiredMiB = [Math]::Max($MemoryMiB, 2560) + 512
if ($freeMiB -lt $requiredMiB) {
    throw "Free RAM is ${freeMiB}MiB; finish heavy builds or close unused applications before starting the AVD"
}
$arguments = @(
    '-avd', 'better_zslf_api36_x86_64',
    '-port', "$Port", '-memory', "$MemoryMiB", '-cores', "$Cores",
    '-no-audio', '-no-snapshot', '-no-boot-anim', '-gpu', 'software',
    '-netdelay', 'none', '-netspeed', 'full'
)
if (-not $Window) { $arguments += '-no-window' }
if ($SoftwareCpu) { $arguments += @('-accel', 'off') }
# Foreground by design: caller owns the process and can stop it with Ctrl+C.
# In DSH, start this command as a tracked background job and stop that job later.
& $emulator @arguments
if ($LASTEXITCODE -ne 0) { throw "Android emulator exited with code $LASTEXITCODE" }
