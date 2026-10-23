#Requires -Version 7.2
[CmdletBinding()]
param(
    [ValidateSet('36', '37.0', '37.1', '37.2')]
    [string[]] $PlatformApi = @('36', '37.2')
)
# Recovery-friendly portable SDK installer. Google's repository XML publishes
# package lengths and SHA1 values; both are checked before opening each ZIP.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$local = Join-Path $root '.local'
$downloads = Join-Path $local 'downloads'
$sdk = Join-Path $local 'android-sdk'
$env:JAVA_HOME = Join-Path $local 'jdk-21'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_USER_HOME = Join-Path $local 'android-user'
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
$ProgressPreference = 'SilentlyContinue'
New-Item -ItemType Directory -Force -Path $downloads, $sdk, $env:ANDROID_USER_HOME | Out-Null
$response = Invoke-WebRequest 'https://dl.google.com/android/repository/repository2-3.xml' -MaximumRetryCount 3
$text = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { $response.Content }
[xml] $repository = $text
function Register-OfficialPackage([System.Xml.XmlElement] $RemotePackage, [string] $Destination) {
    $metadata = Join-Path $Destination 'package.xml'
    if (Test-Path -LiteralPath $metadata) { return }
    # New minor-API platform source.properties are not understood by legacy
    # scanning. Preserve Google's full type-details/revision in local metadata.
    $document = [System.Xml.XmlDocument]::new()
    $repoRoot = $document.CreateElement('localRepo', 'repository', 'http://schemas.android.com/repository/android/common/02')
    foreach ($attribute in $repository.DocumentElement.Attributes) {
        if ($attribute.Prefix -eq 'xmlns' -or $attribute.Name -eq 'xmlns') {
            [void] $repoRoot.Attributes.Append($document.ImportNode($attribute, $true))
        }
    }
    [void] $document.AppendChild($repoRoot)
    $licenseRef = $RemotePackage.SelectSingleNode('uses-license').GetAttribute('ref')
    $license = $repository.SelectSingleNode("//*[local-name()='license' and @id='$licenseRef']")
    if ($null -ne $license) { [void] $repoRoot.AppendChild($document.ImportNode($license, $true)) }
    $packageNode = $document.CreateElement('localPackage')
    $packageNode.SetAttribute('path', $RemotePackage.GetAttribute('path'))
    $packageNode.SetAttribute('obsolete', 'false')
    foreach ($name in 'type-details', 'revision', 'display-name', 'uses-license', 'dependencies') {
        $node = $RemotePackage.SelectSingleNode($name)
        if ($null -ne $node) { [void] $packageNode.AppendChild($document.ImportNode($node, $true)) }
    }
    [void] $repoRoot.AppendChild($packageNode)
    $document.Save($metadata)
    Write-Host "Registered official local package metadata for $($RemotePackage.GetAttribute('path'))"
}
$packages = @(
    @{ Id = 'platform-tools'; Destination = 'platform-tools'; CheckFile = 'adb.exe' },
    @{ Id = 'build-tools;36.0.0'; Destination = 'build-tools\36.0.0'; CheckFile = 'aapt2.exe' }
)
$packages += @($PlatformApi | ForEach-Object {
    @{ Id = "platforms;android-$_"; Destination = "platforms\android-$_"; CheckFile = 'android.jar' }
})
foreach ($spec in $packages) {
    $package = @($repository.SelectNodes('//*[local-name()="remotePackage"]') | Where-Object { $_.path -eq $spec.Id })
    if ($package.Count -ne 1) { throw "Unexpected SDK repository package count for $($spec.Id)" }
    $archive = @($package[0].archives.archive | Where-Object {
        $hostNode = $_.SelectSingleNode('host-os')
        $null -eq $hostNode -or $hostNode.InnerText -eq 'windows'
    })
    if ($archive.Count -ne 1) { throw "Unexpected Windows archive count for $($spec.Id)" }
    $complete = $archive[0].complete
    $name = [string] $complete.url
    if ($name -notmatch '^[a-zA-Z0-9_.-]+\.zip$' -or $complete.checksum.type -ne 'sha1') {
        throw 'Unsupported official SDK archive metadata'
    }
    $sha1 = [string] $complete.checksum.InnerText
    $expectedSize = [long] $complete.size
    $zip = Join-Path $downloads $name
    $valid = (Test-Path -LiteralPath $zip) -and
        (Get-Item -LiteralPath $zip).Length -eq $expectedSize -and
        (Get-FileHash -LiteralPath $zip -Algorithm SHA1).Hash -eq $sha1
    if (-not $valid) {
        $partial = "$zip.partial"
        & curl.exe --fail --location --connect-timeout 20 --max-time 300 --retry 4 --retry-all-errors --silent --show-error `
            --output $partial "https://dl.google.com/android/repository/$name"
        if ($LASTEXITCODE -ne 0) { throw "SDK download failed for $name" }
        if ((Get-Item -LiteralPath $partial).Length -ne $expectedSize -or
            (Get-FileHash -LiteralPath $partial -Algorithm SHA1).Hash -ne $sha1) {
            Remove-Item -LiteralPath $partial -Force
            throw "SDK size/SHA1 mismatch for $name"
        }
        Move-Item -LiteralPath $partial -Destination $zip -Force
    }
    $sha256 = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{ id = $spec.Id; url = "https://dl.google.com/android/repository/$name";
        size = $expectedSize; publishedSha1 = $sha1; calculatedSha256 = $sha256 } |
        ConvertTo-Json | Set-Content -LiteralPath "$zip.verified.json" -Encoding utf8
    Write-Host "Verified size/SHA1 for $name; SHA256=$sha256"
    $destination = Join-Path $sdk $spec.Destination
    if (-not (Test-Path -LiteralPath (Join-Path $destination $spec.CheckFile))) {
        $stage = Join-Path $local ('unpack-' + ($spec.Id -replace '[^a-zA-Z0-9]', '-'))
        New-Item -ItemType Directory -Force -Path $stage | Out-Null
        Expand-Archive -LiteralPath $zip -DestinationPath $stage -Force
        $entries = @(Get-ChildItem -LiteralPath $stage -Directory)
        if ($entries.Count -ne 1) { throw "Unexpected archive root for $name" }
        # A failed sdkmanager unzip can leave a partial target; replace only that
        # exact SDK package directory, after the replacement archive is verified.
        if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Recurse -Force }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Move-Item -LiteralPath $entries[0].FullName -Destination $destination
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
    Register-OfficialPackage $package[0] $destination
}
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "--sdk_root=$sdk" --list_installed
if ($LASTEXITCODE -ne 0) { throw 'SDK inventory validation failed' }
& "$sdk\platform-tools\adb.exe" version
if ($LASTEXITCODE -ne 0) { throw 'Portable adb validation failed' }
& "$sdk\build-tools\36.0.0\aapt2.exe" version
if ($LASTEXITCODE -ne 0) { throw 'Portable build-tools validation failed' }
Write-Host 'Portable Android SDK packages verified and installed.'
