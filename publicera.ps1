# Publicerar en ny version av Agréns filmtajm till familjen.
#
# Gör så här:
#   1. Höj versionCode och versionName i app\build.gradle.kts (Claude hjälper till).
#   2. Bygg installationsfilen i Android Studio:
#      Build -> Generate Signed App Bundle or APK -> APK -> release
#      (samma nyckel som EsterNotes: esternotes-release.jks, alias esternotes).
#   3. Kör i ett vanligt PowerShell-fönster, från projektmappen:
#      powershell -ExecutionPolicy Bypass -File .\publicera.ps1 -Nyheter "Det här är nytt"
#
# Skriptet kontrollerar filen, laddar upp den och versionslappen till GitHub
# och sparar en kopia i installationsfilsmappen. Då får alla som har appen
# frågan "Det finns en ny version" nästa gång de öppnar den.

param(
    [Parameter(Mandatory = $true)][string]$Nyheter
)

$ErrorActionPreference = "Stop"
$projekt = $PSScriptRoot
$repo = "fredrikagren-duvvan/agrens-filmtajm-releases"
$paket = "se.minapp.filmtajm"
# Release-nyckelns fingeravtryck (samma nyckel som EsterNotes).
$releaseSha256 = "56:B9:0D:73:D6:E0:81:CD:49:C6:E2:0E:57:48:09:8C:52:80:E5:A6:E2:B3:CE:50:96:55:9C:C5:19:BF:08:53"
$sdk = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$gh = "C:\Program Files\GitHub CLI\gh.exe"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

function Steg($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function Stopp($text) { Write-Host ""; Write-Host "STOPP: $text" -ForegroundColor Red; exit 1 }

# --- Vilken fil och vilken version? ---
# Android Studio kan lägga filen på två ställen – ta den nyaste.
$apk = @("app\release\app-release.apk", "app\build\outputs\apk\release\app-release.apk") |
    ForEach-Object { Join-Path $projekt $_ } | Where-Object { Test-Path $_ } |
    Sort-Object { (Get-Item $_).LastWriteTime } -Descending | Select-Object -First 1
if (-not $apk) { Stopp "Hittar ingen installationsfil (app-release.apk).`nBygg den först i Android Studio." }
Write-Host "Använder $apk"

Steg "Kontrollerar installationsfilen"
$badging = & "$sdk\aapt2.exe" dump badging $apk | Select-Object -First 1
if ($badging -notmatch "name='se\.minapp\.filmtajm' versionCode='(\d+)' versionName='([^']+)'") {
    Stopp "Filen ser inte ut att vara Agréns filmtajm."
}
$versionCode = [int]$Matches[1]
$versionName = $Matches[2]
Write-Host "Version $versionName (nummer $versionCode)"

$certifikat = cmd /c "`"$sdk\apksigner.bat`" verify --print-certs `"$apk`" 2>nul"
$sha = ($certifikat | Select-String "SHA-256 digest: (\w+)").Matches[0].Groups[1].Value.ToUpper()
$sha = ($sha -split '(..)' | Where-Object { $_ }) -join ':'
if ($sha -ne $releaseSha256) {
    Stopp "Filen är inte signerad med din release-nyckel. Bygg den med 'Generate Signed App Bundle or APK' (release)."
}
Write-Host "Signaturen stämmer."

# --- Finns förrådet på GitHub? Annars skapas det (publikt, bara installationsfiler). ---
# (Körs via cmd så att svaret "finns inte" inte stoppar skriptet i PowerShell 5.1.)
cmd /c "`"$gh`" repo view $repo >nul 2>&1"
if ($LASTEXITCODE -ne 0) {
    Steg "Skapar förrådet $repo på GitHub"
    # --add-readme: en release kräver att förrådet inte är helt tomt.
    & $gh repo create $repo --public --add-readme --description "Installationsfiler för appen Agréns filmtajm (ingen kod)"
    if ($LASTEXITCODE -ne 0) { Stopp "Kunde inte skapa förrådet. Är du inloggad i GitHub (gh auth login)?" }
} else {
    # Är versionen nyare än den som redan är publicerad?
    try {
        $publicerad = Invoke-RestMethod "https://github.com/$repo/releases/latest/download/version.json" -Headers @{ "Cache-Control" = "no-cache" }
        if ($versionCode -le [int]$publicerad.versionCode) {
            Stopp "Version $versionCode är inte nyare än den publicerade ($($publicerad.versionCode)). Höj versionCode i app\build.gradle.kts och bygg igen."
        }
    } catch [System.Net.WebException] {
        Write-Host "(Ingen tidigare publicerad version.)"
    }
}

# --- Versionslappen som appen läser ---
$filnamn = "AgrensFilmtajm-$versionName.apk"
$tagg = "v$versionName"
$tmp = Join-Path $env:TEMP "filmtajm-publicera"
New-Item -ItemType Directory -Force $tmp | Out-Null
Copy-Item $apk (Join-Path $tmp $filnamn) -Force
$lapp = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkUrl      = "https://github.com/$repo/releases/download/$tagg/$filnamn"
    notes       = $Nyheter
}
$lappFil = Join-Path $tmp "version.json"
[IO.File]::WriteAllText($lappFil, ($lapp | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))

# --- Ladda upp till GitHub ---
Steg "Laddar upp $filnamn och versionslappen till GitHub"
& $gh release create $tagg (Join-Path $tmp $filnamn) $lappFil --repo $repo --title "Agréns filmtajm $versionName" --notes $Nyheter --latest
if ($LASTEXITCODE -ne 0) { Stopp "Uppladdningen till GitHub misslyckades." }
Remove-Item $tmp -Recurse -Force

$kopior = "C:\Users\Fredr\AgrensFilmtajm-installationsfiler"
New-Item -ItemType Directory -Force $kopior | Out-Null
Copy-Item $apk (Join-Path $kopior $filnamn) -Force
Write-Host "Kopia sparad i $kopior"

Write-Host ""
Write-Host "Klart! Version $versionName är publicerad." -ForegroundColor Green
Write-Host "Direktlänk att skicka till familjen:"
Write-Host "  $($lapp.apkUrl)"
Write-Host "Alla som redan har appen får frågan om uppdatering nästa gång de öppnar den."
