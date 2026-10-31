# Builds the Android app. Runs Gradle on the CPU's efficiency cores with few threads:
# this PC's i9-14900K crashes under heavy all-core load (see README).
#   powershell -ExecutionPolicy Bypass -File android\build.ps1 [gradle tasks...]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks)
$ErrorActionPreference = 'Stop'
if (-not $Tasks) { $Tasks = @(':app:assembleDebug') }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17' }
Set-Location $PSScriptRoot
$cores = [Environment]::ProcessorCount
# Logical CPUs 16-31 are the E-cores on a 14900K; elsewhere use the upper half.
$mask = if ($cores -ge 32) { 0xFFFF0000L } else { ((1L -shl $cores) - 1) -bxor ((1L -shl [int]($cores / 2)) - 1) }
# Child processes (gradlew, the Gradle and Kotlin daemons) inherit this affinity.
[System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity = [IntPtr]$mask
& "$PSScriptRoot\gradlew.bat" @Tasks --console=plain --max-workers=2
exit $LASTEXITCODE
