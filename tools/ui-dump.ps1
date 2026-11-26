# Dump current screen: text @ center coords (Android app under test)
$adb = 'D:\dev\android-toolchain\sdk\platform-tools\adb.exe'
& $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
$xml = (& $adb shell cat /sdcard/ui.xml) -join ''
foreach ($node in ($xml -split '><')) {
    if ($node -notmatch 'text="([^"]+)"') { continue }
    $text = $Matches[1]
    if ($node -notmatch 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') { continue }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    $clickable = if ($node -match 'clickable="true"') { 'click' } else { '' }
    '{0,-45} {1,5},{2,-5} {3}' -f $text, $x, $y, $clickable
}
