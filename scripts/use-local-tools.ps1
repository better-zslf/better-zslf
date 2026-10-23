# Dot-source this file: . ./scripts/use-local-tools.ps1
# Values apply only to the current PowerShell process; nothing is persisted.
$root = Split-Path -Parent $PSScriptRoot
$local = Join-Path $root '.local'
$env:JAVA_HOME = Join-Path $local 'jdk-21'
$env:GRADLE_USER_HOME = Join-Path $local 'gradle-user-home'
$env:ANDROID_HOME = Join-Path $local 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $local 'android-user'
# AGP treats PREFS_ROOT as a parent, unlike USER_HOME: never set both.
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $local 'avd'
$env:KONAN_DATA_DIR = Join-Path $local 'konan'
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe")) {
    throw 'Portable JDK missing. Run scripts/bootstrap-windows.ps1 first.'
}
$toolPaths = @(
    "$env:JAVA_HOME\bin",
    "$local\gradle-9.6.1\bin",
    "$env:ANDROID_HOME\cmdline-tools\latest\bin",
    "$env:ANDROID_HOME\platform-tools"
)
$env:PATH = ($toolPaths -join [IO.Path]::PathSeparator) + [IO.Path]::PathSeparator + $env:PATH
Write-Host 'Workspace-local Java/Gradle/Android environment activated for this shell only.'
