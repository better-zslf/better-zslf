# Offline JVM execution of the actual pure iosMain rules. This does not build the KMP project.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$kotlinHome = if ($env:KOTLIN_HOME) { $env:KOTLIN_HOME } else { Join-Path $repoRoot '.local/kotlin-2.4.21/kotlinc' }
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path $repoRoot '.local/jdk-21' }
$javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
$java = Join-Path $javaHome "bin/$javaName"
if (-not (Test-Path $java)) { throw 'Set JAVA_HOME to a JDK 21 installation.' }
if (-not (Test-Path (Join-Path $kotlinHome 'lib/kotlin-compiler.jar'))) { throw 'Set KOTLIN_HOME to a Kotlin compiler installation.' }
$output = Join-Path $repoRoot 'iosApp/.generated/capability-rules-tests.jar'
New-Item -ItemType Directory -Force (Split-Path $output -Parent) | Out-Null
& $java "-Dkotlin.home=$kotlinHome" -cp "$kotlinHome/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    (Join-Path $repoRoot 'shared/src/iosMain/kotlin/io/github/betterzslf/platform/IosCapabilityRules.kt') `
    (Join-Path $repoRoot 'iosApp/tests/CapabilityRulesTest.kt') -include-runtime -d $output
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -jar $output
exit $LASTEXITCODE
