param([string]$QingSerial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
if ($QingSerial -notmatch '^emulator-\d+$') { throw 'QA helper only supports an emulator' }
$qingAvdName = & adb -s $QingSerial emu avd name
if ($qingAvdName -notcontains 'Qingping_API35') { throw 'Use the isolated Qingping_API35 AVD' }

function Get-QingUi {
    for ($qingAttempt = 0; $qingAttempt -lt 5; $qingAttempt++) {
        $qingRaw = (& adb -s $QingSerial exec-out uiautomator dump /dev/tty) -join "`n"
        $qingMatch = [regex]::Match($qingRaw, '(?s)<hierarchy.*?</hierarchy>')
        if ($qingMatch.Success) { return ,([xml]$qingMatch.Value) }
    }
    throw 'No UI tree available'
}

function Invoke-QingTap([string]$Text, [switch]$IfPresent) {
    $qingUi = Get-QingUi
    $qingNode = $qingUi.SelectNodes('//*[@text]') | Where-Object text -EQ $Text | Select-Object -First 1
    if (-not $qingNode) {
        if ($IfPresent) { return $false }
        throw "UI text not found: $Text"
    }
    $qingBounds = [regex]::Matches($qingNode.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & adb -s $QingSerial shell input tap ([int](($qingBounds[0]+$qingBounds[2])/2)) ([int](($qingBounds[1]+$qingBounds[3])/2)) | Out-Null
    return $true
}
