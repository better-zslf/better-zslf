#Requires -Version 7.2
[CmdletBinding()]
param(
    [ValidateSet('Emulator', 'Image', 'Avd')]
    [string[]] $Component = @('Emulator', 'Image', 'Avd')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'use-local-tools.ps1')
$ProgressPreference = 'SilentlyContinue'
$downloads = Join-Path $local 'downloads'
New-Item -ItemType Directory -Force -Path $downloads, $env:ANDROID_AVD_HOME | Out-Null

function Get-AndroidZip([string] $Name, [string] $Uri, [long] $Size, [string] $Sha1) {
    $zip = Join-Path $downloads $Name
    $valid = (Test-Path -LiteralPath $zip) -and (Get-Item -LiteralPath $zip).Length -eq $Size -and
        (Get-FileHash -LiteralPath $zip -Algorithm SHA1).Hash -eq $Sha1
    if (-not $valid) {
        $partial = "$zip.partial"
        & curl.exe --fail --location --connect-timeout 20 --max-time 1800 --retry 4 --retry-all-errors `
            --continue-at - --silent --show-error --output $partial $Uri
        if ($LASTEXITCODE -ne 0) { throw "Download failed for $Name" }
        if ((Get-Item -LiteralPath $partial).Length -ne $Size -or
            (Get-FileHash -LiteralPath $partial -Algorithm SHA1).Hash -ne $Sha1) {
            Remove-Item -LiteralPath $partial -Force
            throw "Official size/SHA1 verification failed for $Name"
        }
        Move-Item -LiteralPath $partial -Destination $zip -Force
    }
    $sha256 = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{ url = $Uri; size = $Size; publishedSha1 = $Sha1; calculatedSha256 = $sha256 } |
        ConvertTo-Json | Set-Content -LiteralPath "$zip.verified.json" -Encoding utf8
    Write-Host "Verified size/SHA1 $Name; SHA256=$sha256"
    return $zip
}

if ($Component -contains 'Emulator') {
    # Stable channel metadata: https://dl.google.com/android/repository/repository2-3.xml
    $zip = Get-AndroidZip 'emulator-windows_x64-16428233.zip' `
        'https://dl.google.com/android/repository/emulator-windows_x64-16428233.zip' `
        455342868 '488ed747e82de7e9bb5247becd1ac043c7e5e85d'
    if (-not (Test-Path -LiteralPath "$env:ANDROID_HOME\emulator\emulator.exe")) {
        Expand-Archive -LiteralPath $zip -DestinationPath $env:ANDROID_HOME -Force
    }
    # Unlike older platform archives, the emulator ZIP has no package.xml and
    # current sdkmanager does not infer its generic package from source.properties.
    # Register only the downloaded official package/revision so avdmanager sees it.
    if (-not (Test-Path -LiteralPath "$env:ANDROID_HOME\emulator\package.xml")) {
        $packageXml = @(
            '<?xml version="1.0" encoding="UTF-8"?>',
            '<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:generic="http://schemas.android.com/repository/android/generic/02" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">',
            '<license id="android-sdk-license" type="text"/>',
            '<localPackage path="emulator" obsolete="false">',
            '<type-details xsi:type="generic:genericDetailsType"/>',
            '<revision><major>37</major><minor>2</minor><micro>12</micro></revision>',
            '<display-name>Android Emulator</display-name><uses-license ref="android-sdk-license"/>',
            '</localPackage></ns2:repository>'
        ) -join "`n"
        Set-Content -LiteralPath "$env:ANDROID_HOME\emulator\package.xml" -Value $packageXml -Encoding utf8
    }
    & "$env:ANDROID_HOME\emulator\emulator.exe" -version
    if ($LASTEXITCODE -ne 0) { throw 'Emulator version validation failed' }
    & "$env:ANDROID_HOME\emulator\emulator.exe" -accel-check
    if ($LASTEXITCODE -ne 0) {
        Write-Warning 'Hardware acceleration is not ready; downloaded tools remain installed and no Windows features were changed.'
    }
}

if ($Component -contains 'Image') {
    # AOSP API36 image: no Play account/services, smallest official x86_64 image.
    # Metadata: https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml
    $zip = Get-AndroidZip 'aosp-x86_64-36_r02.zip' `
        'https://dl.google.com/android/repository/sys-img/android/x86_64-36_r02.zip' `
        844217077 '829c076e8ff448a336097ae25a355b495ba36e2c'
    $target = Join-Path $env:ANDROID_HOME 'system-images\android-36\default'
    if (-not (Test-Path -LiteralPath "$target\x86_64\system.img")) {
        New-Item -ItemType Directory -Force -Path $target | Out-Null
        Expand-Archive -LiteralPath $zip -DestinationPath $target -Force
    }
    Write-Host 'AOSP API36 x86_64 system image is ready.'
}

if ($Component -contains 'Avd') {
    $avdName = 'better_zslf_api36_x86_64'
    $avdPath = Join-Path $env:ANDROID_AVD_HOME "$avdName.avd"
    if (-not (Test-Path -LiteralPath "$avdPath\config.ini")) {
        'no' | & "$env:ANDROID_HOME\cmdline-tools\latest\bin\avdmanager.bat" create avd `
            --name $avdName --package 'system-images;android-36;default;x86_64' `
            --path $avdPath --device 'pixel_7' --force
        if ($LASTEXITCODE -ne 0) { throw 'Workspace AVD creation failed' }
    }
    # A small display limits host GPU/RAM cost on the 16GiB development laptop.
    $configFile = Join-Path $avdPath 'config.ini'
    $config = Get-Content -LiteralPath $configFile -Raw
    foreach ($setting in @{
        'hw.lcd.width' = '768'; 'hw.lcd.height' = '1280'; 'hw.lcd.density' = '240';
        'hw.ramSize' = '1536'; 'hw.cpu.ncore' = '2'; 'hw.gpu.enabled' = 'yes';
        'hw.gpu.mode' = 'software'; 'showDeviceFrame' = 'no'; 'disk.dataPartition.size' = '4G'
    }.GetEnumerator()) {
        $pattern = '(?m)^' + [regex]::Escape($setting.Key) + '=.*$'
        if ($config -match $pattern) { $config = [regex]::Replace($config, $pattern, "$($setting.Key)=$($setting.Value)") }
        else { $config += "`n$($setting.Key)=$($setting.Value)" }
    }
    Set-Content -LiteralPath $configFile -Value $config -Encoding utf8
    & "$env:ANDROID_HOME\emulator\emulator.exe" -list-avds
    if ($LASTEXITCODE -ne 0) { throw 'Workspace AVD inventory failed' }
    Write-Host 'Run scripts/run-emulator.ps1 to start the headless AVD; no project build was run.'
}
