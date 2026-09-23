# One command on the dev PC: push main, publish the server, build the release APK, upload it.
#   powershell -ExecutionPolicy Bypass -File deploy/release.ps1
#   powershell -ExecutionPolicy Bypass -File deploy/release.ps1 -ServerOnly
#   powershell -ExecutionPolicy Bypass -File deploy/release.ps1 -ApkOnly -Notes "说明"
#   powershell -ExecutionPolicy Bypass -File deploy/release.ps1 -WithStt
# Refuses a dirty worktree unless -AllowDirty. Server AOT publish restarts chatter (about 3 minutes).
param(
    [switch]$ServerOnly,
    [switch]$ApkOnly,
    [switch]$WithStt,
    [switch]$AllowDirty,
    [string]$Notes = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$EnvFile = Join-Path $PSScriptRoot "local.env"
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) { return }
        $k, $v = $line.Split("=", 2)
        Set-Item -Path "Env:$($k.Trim())" -Value $v.Trim().Trim('"').Trim("'")
    }
}
$HostName = if ($env:CHATTER_HOST) { $env:CHATTER_HOST } else { "YOUR_SERVER_IP" }
$Ssh = @("-o", "BatchMode=yes", "-o", "ConnectTimeout=20")
if ($env:CHATTER_SSH_PORT) { $Ssh += @("-p", $env:CHATTER_SSH_PORT) }
$Ssh += $HostName

function Invoke-Checked([string]$Label, [scriptblock]$Action) {
    Write-Host "== $Label"
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "$Label failed (exit $LASTEXITCODE)" }
}

$dirty = git status --porcelain
if ($dirty -and -not $AllowDirty) {
    Write-Host "!! worktree is dirty; commit first, or pass -AllowDirty"
    git status -sb
    exit 1
}

$gradle = Get-Content -Raw "android\app\build.gradle.kts"
if ($gradle -notmatch 'versionName = "([^"]+)"') { throw "versionName not found" }
$versionName = $Matches[1]
if ($gradle -notmatch 'versionCode = (\d+)') { throw "versionCode not found" }
$versionCode = $Matches[1]
if (-not $Notes) {
    $Notes = (git log -1 --pretty=%s).Trim()
    if (-not $Notes) { $Notes = "lochatter $versionName" }
}
Write-Host "== release $versionName ($versionCode): $Notes"

if (-not $ApkOnly) {
    Invoke-Checked "git push server main" { git push server main }
    Invoke-Checked "server deploy.sh" { ssh @Ssh "bash /opt/chatter/src/deploy/deploy.sh main" }
    if ($WithStt) {
        Invoke-Checked "stt-setup.sh" { ssh @Ssh "bash /opt/chatter/src/deploy/stt-setup.sh" }
    }
    Invoke-Checked "healthz" {
        ssh @Ssh "curl -fsS http://127.0.0.1:5088/healthz; echo"
    }
}

if (-not $ServerOnly) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
    $env:PATH = "C:\Android\gradle\bin;" + $env:PATH
    Invoke-Checked "gradle assembleRelease" {
        Push-Location android
        try { gradle --no-daemon --console=plain :app:assembleRelease }
        finally { Pop-Location }
    }

    $apk = Join-Path $env:LOCALAPPDATA "chatter-build\out\app\outputs\apk\release\app-release.apk"
    $props = Join-Path $env:USERPROFILE ".gradle\gradle.properties"
    if (Test-Path $props) {
        foreach ($line in Get-Content $props) {
            if ($line -match '^\s*chatter\.buildDir\s*=\s*(.+)\s*$') {
                $apk = Join-Path ($Matches[1] -replace '/', '\') "app\outputs\apk\release\app-release.apk"
            }
        }
    }
    if (-not (Test-Path $apk)) {
        $fallback = Join-Path $Root "android\app\build\outputs\apk\release\app-release.apk"
        if (Test-Path $fallback) { $apk = $fallback } else { throw "APK not found: $apk" }
    }

    $remote = "/tmp/chatter-$versionName.apk"
    Invoke-Checked "upload apk" { scp -o BatchMode=yes -o ConnectTimeout=20 $apk "${HostName}:$remote" }
    $notesArg = $Notes -replace "'", "'\\''"
    Invoke-Checked "publish-apk.sh" {
        ssh @Ssh "bash /opt/chatter/src/deploy/publish-apk.sh '$remote' '$versionName' '$versionCode' '$notesArg'"
    }
}

Write-Host "== done $versionName"
