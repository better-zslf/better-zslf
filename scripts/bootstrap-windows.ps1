#Requires -Version 7.2
[CmdletBinding()]
param(
    [ValidateSet('Jdk', 'Gradle', 'Android')]
    [string[]] $Component = @('Jdk', 'Gradle', 'Android')
)

# Portable tools and caches stay under the repository. This script never edits
# the registry, machine/user PATH, global JAVA_HOME, or global Android settings.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$local = Join-Path $root '.local'
$downloads = Join-Path $local 'downloads'
New-Item -ItemType Directory -Force -Path $downloads | Out-Null
$ProgressPreference = 'SilentlyContinue'

function Get-RemoteText([string] $Uri) {
    $response = Invoke-WebRequest -Uri $Uri -MaximumRetryCount 3
    if ($response.Content -is [byte[]]) {
        return [Text.Encoding]::UTF8.GetString($response.Content).Trim()
    }
    return ([string] $response.Content).Trim()
}

function Get-VerifiedArchive([string] $Uri, [string] $Name, [string] $Sha256) {
    if ($Sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw "Invalid published SHA256 for $Name" }
    $destination = Join-Path $downloads $Name
    if ((Test-Path -LiteralPath $destination) -and
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $Sha256) {
        Write-Host "Verified cached $Name"
        return $destination
    }
    $partial = "$destination.partial"
    Write-Host "Downloading $Name from official distribution"
    try {
        Invoke-WebRequest -Uri $Uri -OutFile $partial -MaximumRetryCount 3
    } catch {
        # curl also handles transient TLS/EOF failures seen with large assets.
        if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { throw }
        & curl.exe --fail --location --retry 4 --retry-all-errors --silent --show-error --output $partial $Uri
        if ($LASTEXITCODE -ne 0) { throw "Download failed for $Name" }
    }
    $actual = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Sha256.ToLowerInvariant()) {
        Remove-Item -LiteralPath $partial -Force
        throw "SHA256 mismatch for $Name"
    }
    Move-Item -LiteralPath $partial -Destination $destination -Force
    [ordered]@{ name = $Name; url = $Uri; algorithm = 'SHA256'; sha256 = $actual } |
        ConvertTo-Json | Set-Content -LiteralPath "$destination.verified.json" -Encoding utf8
    Write-Host "Verified SHA256 $Name = $actual"
    return $destination
}

if ($Component -contains 'Jdk') {
    $jdkZip = Get-VerifiedArchive `
        'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip' `
        'OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip' `
        'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
    $jdk = Join-Path $local 'jdk-21'
    if (-not (Test-Path -LiteralPath "$jdk\bin\javac.exe")) {
        $stage = Join-Path $local 'jdk-unpack'
        New-Item -ItemType Directory -Force -Path $stage | Out-Null
        Expand-Archive -LiteralPath $jdkZip -DestinationPath $stage -Force
        $expanded = @(Get-ChildItem -LiteralPath $stage -Directory)
        if ($expanded.Count -ne 1) { throw 'Unexpected JDK archive layout' }
        Move-Item -LiteralPath $expanded[0].FullName -Destination $jdk
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
    & "$jdk\bin\java.exe" -version
    if ($LASTEXITCODE -ne 0) { throw 'Portable JDK validation failed' }
    & "$jdk\bin\javac.exe" -version
    if ($LASTEXITCODE -ne 0) { throw 'Portable javac validation failed' }
}

if ($Component -contains 'Gradle') {
    $gradleVersion = '9.6.1'
    # Published checksum: https://downloads.gradle.org/distributions/gradle-9.6.1-bin.zip.sha256
    $sha256 = '9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14'
    $gradleZip = Get-VerifiedArchive `
        "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" `
        "gradle-$gradleVersion-bin.zip" $sha256
    if (-not (Test-Path -LiteralPath "$local\gradle-$gradleVersion\bin\gradle.bat")) {
        Expand-Archive -LiteralPath $gradleZip -DestinationPath $local -Force
    }
    # --version is an environment check, not a project configuration/build.
    $env:JAVA_HOME = Join-Path $local 'jdk-21'
    $env:GRADLE_USER_HOME = Join-Path $local 'gradle-user-home'
    & "$local\gradle-$gradleVersion\bin\gradle.bat" --version
    if ($LASTEXITCODE -ne 0) { throw 'Portable Gradle validation failed' }
}

if ($Component -contains 'Android') {
    $env:JAVA_HOME = Join-Path $local 'jdk-21'
    if (-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe")) {
        throw 'Install the Jdk component before Android'
    }
    $sdk = Join-Path $local 'android-sdk'
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $env:ANDROID_USER_HOME = Join-Path $local 'android-user'
    Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $sdk, $env:ANDROID_USER_HOME | Out-Null
    # SHA256 is published at https://developer.android.com/studio (command line tools).
    $toolsZip = Get-VerifiedArchive `
        'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' `
        'commandlinetools-win-15859902_latest.zip' `
        '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $tools = Join-Path $sdk 'cmdline-tools\latest'
    if (-not (Test-Path -LiteralPath "$tools\bin\sdkmanager.bat")) {
        $stage = Join-Path $local 'android-tools-unpack'
        New-Item -ItemType Directory -Force -Path $stage, (Split-Path -Parent $tools) | Out-Null
        Expand-Archive -LiteralPath $toolsZip -DestinationPath $stage -Force
        Move-Item -LiteralPath "$stage\cmdline-tools" -Destination $tools
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
    $sdkmanager = "$tools\bin\sdkmanager.bat"
    # sdkmanager validates each package against Google's repository checksums.
    # All licenses/cache/package state are scoped by --sdk_root / ANDROID_USER_HOME.
    1..100 | ForEach-Object { 'y' } | & $sdkmanager "--sdk_root=$sdk" --licenses
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK license setup failed' }
    # Verify archive lengths + published checksums explicitly before unzip.
    # This also recovers from sdkmanager's transient incomplete-ZIP failures.
    & (Join-Path $PSScriptRoot 'install-android-packages.ps1')
}

Write-Host 'Portable tools ready. Dot-source scripts/use-local-tools.ps1 in the build shell.'
