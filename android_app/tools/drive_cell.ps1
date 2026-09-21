param(
  [Parameter(Mandatory=$true)][string]$Cell,
  [string]$List = "To shoot*",
  [int]$TimeoutSec = 120
)
# Fire one TestPlan cell on the connected phone and wait for its receipt.
# Every button is located from a live uiautomator dump: START moves with the instruction
# text's length, Done-list rows carry a "✓  " prefix, and nothing here assumes coordinates
# except the overflow menu at the top right of a 1440x3120 screen.
$ErrorActionPreference = "Stop"
$B = "/sdcard/Android/data/se.lth.math.videoimucapture/files"
$marker = "/sdcard/cell_marker_$Cell"

function Dump { adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null; adb shell cat /sdcard/ui.xml > $env:TEMP\ui.xml; [xml](Get-Content $env:TEMP\ui.xml) }
function TapNode($x, $pattern) {
  $n = $x.SelectNodes('//node') | Where-Object { $_.text -like $pattern } | Select-Object -First 1
  if (-not $n) { throw "no node matching '$pattern'" }
  if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $cx=([int]$matches[1]+[int]$matches[3])/2; $cy=([int]$matches[2]+[int]$matches[4])/2
    adb shell input tap $cx $cy
    "  tapped '$(($n.text -replace "`n",' ').Substring(0,[Math]::Min(44,$n.text.Length)))'"
  }
  Start-Sleep -Milliseconds 900
}

adb shell "touch $marker"
adb shell input keyevent KEYCODE_WAKEUP
$kg = (adb shell 'dumpsys window | grep -E "isKeyguardShowing" | head -1') -join ''
if ($kg -match 'true') { throw "phone is locked" }
adb shell am start -n se.lth.math.videoimucapture/.CameraCaptureActivity | Out-Null
Start-Sleep -Seconds 3
adb shell input tap 1354 236; Start-Sleep -Milliseconds 900
TapNode (Dump) 'TESTS'
TapNode (Dump) $List
TapNode (Dump) "*$Cell - *"
adb logcat -c
TapNode (Dump) 'START'
$t0 = Get-Date
"== $Cell started $($t0.ToString('HH:mm:ss'))"

$sealed = $null
while (((Get-Date) - $t0).TotalSeconds -lt $TimeoutSec) {
  Start-Sleep -Seconds 3
  $found = (adb shell "find $B -maxdepth 2 -name session.json -newer $marker 2>/dev/null") -join "`n"
  if ($found -match 'session\.json') { $sealed = ($found -split "`n" | Where-Object { $_ } | Select-Object -First 1).Trim(); break }
}
if (-not $sealed) { throw "${Cell}: no receipt within $TimeoutSec s" }
$dir = Split-Path $sealed -Parent
"== receipt: $dir  (+$([int]((Get-Date) - $t0).TotalSeconds) s)"
adb shell "cat $sealed" | Select-String -Pattern '"git_sha"|"test_cell"|"stills_fired"|"stereo_pairs_armed"|"stereo_meta_rows"|"video"|"video_bytes"|"video_file_complete"|"stills_jpg"|"stereo_halves"|"stereo_pairs_complete"|"lenses_seen"|"meta_written"|"complete"|"camera_error"|"agrees"|"summary"' | ForEach-Object { "   " + $_.Line.Trim() }
$p = (adb shell pidof se.lth.math.videoimucapture) -join ''
$log = adb logcat -d --pid=$p 2>$null | Select-String -Pattern "CAMERA_ERROR|device error|not valid|Exception|FATAL|incomplete|periodic stereo|pair from stream|preview restored|could not restore|focus stack|lens pair sequence|object composite|run stopped|video session|sealing|SessionManifest" | ForEach-Object { $_.Line.Substring(19, [Math]::Min(120, $_.Line.Length-19)) }
"== log:"; $log | Select-Object -Last 24 | ForEach-Object { "   $_" }
