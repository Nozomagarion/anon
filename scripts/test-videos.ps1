param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [string]$Serial = 'emulator-5556',
    [int]$Seconds = 0
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$fixtures = Join-Path $workspace 'build/fixtures'
$results = Join-Path $workspace 'output/tests'
New-Item -ItemType Directory -Force $fixtures, $results | Out-Null
Get-ChildItem -LiteralPath (Join-Path $workspace 'video') -Filter '*.mp4' | ForEach-Object {
    $source = $_
    $fixture = Join-Path $fixtures ($source.BaseName + '-input.mp4')
    $ffargs = @('-hide_banner', '-loglevel', 'error', '-y', '-i', $source.FullName)
    if ($Seconds -gt 0) { $ffargs += @('-t', $Seconds) }
    # Same content and complete duration by default, reduced only for software-emulator test speed.
    $ffargs += @('-vf', 'scale=960:-2', '-c:v', 'libx264', '-preset', 'fast', '-crf', '19', '-c:a', 'aac', $fixture)
    & ffmpeg @ffargs
    if ($LASTEXITCODE -ne 0) { throw "Fixture conversion failed: $($source.Name)" }
    & $Adb -s $Serial push $fixture '/sdcard/Android/data/fr.anon.app/files/test-input.mp4'
    if ($LASTEXITCODE -ne 0) { throw 'adb push failed' }
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $testOutput = & $Adb -s $Serial shell am instrument -w -e class 'fr.anon.app.ReplacementTest#realVideoExportsWithFacesReplacedAndAudioPreserved' 'fr.anon.app.test/androidx.test.runner.AndroidJUnitRunner' 2>&1
    $watch.Stop()
    $testOutput | Set-Content -LiteralPath (Join-Path $results ($source.BaseName + '.txt'))
    & $Adb -s $Serial pull '/sdcard/Android/data/fr.anon.app/files/test-output.mp4' (Join-Path $results ($source.BaseName + '-anon.mp4'))
    [pscustomobject]@{ Video=$source.Name; Passed=($testOutput -match 'OK \(1 test\)').Count -gt 0; Seconds=[math]::Round($watch.Elapsed.TotalSeconds, 1) }
    if (($testOutput -match 'OK \(1 test\)').Count -eq 0) { throw "Video test failed: $($source.Name). See output/tests." }
}
