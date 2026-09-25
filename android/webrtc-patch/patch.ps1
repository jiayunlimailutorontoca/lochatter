param(
    [Parameter(Mandatory = $true)][string]$Aar,
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Out
)
$ErrorActionPreference = "Stop"
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { throw "JAVA_HOME is not set" }
$javac = Join-Path $javaHome "bin\javac.exe"
$jar = Join-Path $javaHome "bin\jar.exe"
$androidJar = "C:\Android\sdk\platforms\android-35\android.jar"
$ann = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\androidx.annotation\annotation-jvm" -Recurse -Filter "annotation-jvm-*.jar" |
    Where-Object { $_.Name -notmatch "sources|javadoc" } |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if (-not $ann) { throw "androidx annotation jar not found" }

$work = Join-Path $env:TEMP "webrtc-patch-work"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Path $work | Out-Null
$classesDir = Join-Path $work "classes"
$compiled = Join-Path $work "compiled"
New-Item -ItemType Directory -Path $classesDir, $compiled | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$aarZip = [System.IO.Compression.ZipFile]::OpenRead($Aar)
$entry = $aarZip.GetEntry("classes.jar")
$classesJar = Join-Path $work "classes.jar"
$srcStream = $entry.Open()
$dstStream = [System.IO.File]::Create($classesJar)
$srcStream.CopyTo($dstStream)
$dstStream.Close()
$srcStream.Close()
$aarZip.Dispose()

Push-Location $classesDir
try {
    & $jar xf $classesJar
    if ($LASTEXITCODE -ne 0) { throw "jar xf failed" }
} finally { Pop-Location }
Get-ChildItem $classesDir -Recurse -Filter "WebRtcAudioRecord*.class" | Remove-Item -Force

$cp = "$androidJar;$classesJar;$($ann.FullName)"
& $javac --release 17 -encoding UTF-8 -classpath $cp -d $compiled $Source
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Copy-Item (Join-Path $compiled "org\webrtc\audio\WebRtcAudioRecord*.class") (Join-Path $classesDir "org\webrtc\audio") -Force

$newJar = Join-Path $work "classes-new.jar"
Push-Location $classesDir
try { & $jar cf $newJar . } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "jar cf failed" }

$outDir = Split-Path -Parent $Out
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
if (Test-Path $Out) { Remove-Item -Force $Out }
$inZip = [System.IO.Compression.ZipFile]::OpenRead($Aar)
$outZip = [System.IO.Compression.ZipFile]::Open($Out, [System.IO.Compression.ZipArchiveMode]::Create)
foreach ($e in $inZip.Entries) {
    if ($e.FullName -eq "classes.jar") { continue }
    $dest = $outZip.CreateEntry($e.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
    $es = $e.Open()
    $ds = $dest.Open()
    $es.CopyTo($ds)
    $ds.Close()
    $es.Close()
}
$inZip.Dispose()
$classesEntry = $outZip.CreateEntry("classes.jar", [System.IO.Compression.CompressionLevel]::Optimal)
$cs = $classesEntry.Open()
$fs = [System.IO.File]::OpenRead($newJar)
$fs.CopyTo($cs)
$fs.Close()
$cs.Close()
$outZip.Dispose()
Write-Output "PATCHED $($newJar.Length)"
