# Séance éclair dans la voiture (≈ 2 min) : à lancer quand le PC et l'autoradio sont sur le même partage de connexion
# et que « ADB par le Wi-Fi » est activé dans les Réglages du Lanceur.
#   1. trouve l'autoradio (port 5555) sur le réseau du PC
#   2. installe l'APK release du projet (vérifie d'abord que ce n'est pas la variante émulateur)
#   3. ouvre l'accueil et capture la vraie 3D sous 3 angles, recadrée sur le panneau voiture (sans kilométrage)
# Lecture seule sur la voiture ; rien n'est modifié dans les réglages de l'autoradio.
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$apk = "$root\projet\lanceur-308\app\build\outputs\apk\release\app-release.apk"
$out = "$root\docs\captures\autoradio"
$tmp = Join-Path $env:TEMP 'voiture_rapide.png'
$t0 = Get-Date

# 1. Autoradio
$w = Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'Wi-Fi' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $w) { 'Pas de Wi-Fi sur le PC.'; exit 1 }
$base = ($w.IPAddress -split '\.')[0..2] -join '.'
$tries = @{}; 1..254 | ForEach-Object { $c = New-Object Net.Sockets.TcpClient; $tries["$base.$_"] = @($c, $c.ConnectAsync("$base.$_", 5555)) }
Start-Sleep 2
$ip = $tries.GetEnumerator() | Where-Object { $_.Value[1].IsCompleted -and $_.Value[0].Connected } | Select-Object -First 1 -ExpandProperty Key
$tries.Values | ForEach-Object { $_[0].Close() }
if (-not $ip) { "Autoradio introuvable sur $base.x : ADB par le Wi-Fi activé ? même partage de connexion ?"; exit 1 }
$dev = "${ip}:5555"
& $adb disconnect | Out-Null; & $adb connect $dev | Out-Null
"Autoradio : $dev ($(& $adb -s $dev get-state))"

# 2. Installation (une APK de plus de 20 Mo est la variante émulateur x86 sans 3D : refusée)
$size = (Get-Item $apk).Length
if ($size -gt 20MB) { "APK de $([math]::Round($size / 1MB)) Mo = variante émulateur : relancer build09.ps1 avant."; exit 1 }
& $adb -s $dev install -r $apk | Select-Object -Last 1
"Version : $((& $adb -s $dev shell dumpsys package fr.p308.lanceur) -match 'versionName' | Select-Object -First 1)"

# 3. Captures de la 3D
New-Item -ItemType Directory -Force $out | Out-Null
Add-Type -AssemblyName System.Drawing
function Shot($name) {
    cmd /c "`"$adb`" -s $dev exec-out screencap -p > `"$tmp`""
    $b = [System.Drawing.Bitmap]::FromFile($tmp)
    $c = $b.Clone((New-Object System.Drawing.Rectangle 92, 68, 700, 644), $b.PixelFormat); $b.Dispose()
    $c.Save("$out\$name.png"); $c.Dispose(); "  capture $name"
}
& $adb -s $dev shell am force-stop fr.p308.lanceur
& $adb -s $dev shell am start -n fr.p308.lanceur/.MainActivity | Out-Null
Start-Sleep 10
& $adb -s $dev shell input tap 46 100; Start-Sleep 4
Shot 'accueil-3-4-avant'
1..4 | ForEach-Object { & $adb -s $dev shell input tap 759 113; Start-Sleep 2 }; Start-Sleep 1
Shot 'accueil-3-4-arriere'
1..2 | ForEach-Object { & $adb -s $dev shell input tap 759 113; Start-Sleep 2 }; Start-Sleep 1
Shot 'accueil-profil'
"Fini en $([math]::Round(((Get-Date) - $t0).TotalSeconds)) s"
