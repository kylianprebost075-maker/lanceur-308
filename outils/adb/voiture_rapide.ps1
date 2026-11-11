# Séance éclair dans la voiture (≈ 2 min) : à lancer quand le PC et l'autoradio sont sur le même partage de connexion
# et que « ADB par le Wi-Fi » est activé dans les Réglages du Lanceur.
#   1. trouve l'autoradio (port 5555) sur le réseau du PC
#   2. installe l'APK release du projet (vérifie d'abord que ce n'est pas la variante émulateur)
#   3. remet le Lanceur en écran d'accueil, active la balance Seul / Passagers (accessibilité, v0.31)
#   4. avec -Captures : ouvre l'accueil et capture la vraie 3D sous 3 angles, recadrée (sans kilométrage)
# Rien n'est envoyé à la voiture elle-même ; seuls le Lanceur et ses autorisations sur l'autoradio sont réglés.
param([switch]$Captures)
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

# 2 bis. Le Lanceur redevient l'écran d'accueil (demande de l'utilisateur) : même effet que Réglages → « Utiliser comme
# écran d'accueil », puis choisi comme accueil par défaut. Réversible : décocher ce réglage dans le Lanceur.
# (« pm enable » est refusé au shell : l'alias reste activé par le réglage du Lanceur, gardé par la mise à jour.)
& $adb -s $dev shell cmd package set-home-activity fr.p308.lanceur/fr.p308.lanceur.HomeAlias | Out-Null
$accueil = (& $adb -s $dev shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) | Select-Object -Last 1
"Écran d'accueil : $accueil"
if ($accueil -notmatch 'fr.p308.lanceur') { "=> Activer Réglages → « Utiliser comme écran d'accueil » dans le Lanceur, puis relancer." }

# 2 ter (v0.31). Balance « Seul / Passagers » : active le service d'accessibilité du Lanceur (il ne touche que
# l'égaliseur FYT, quand on appuie sur Seul ou Passagers ; se coupe dans Android → Accessibilité). Les services déjà
# actifs sont gardés. Puis la position « Seul » trouvée au micro le 11/11 (1 → et 3 ↑) est envoyée au Lanceur.
$svc = 'fr.p308.lanceur/fr.p308.lanceur.sound.ChampService'
$actifs = ((& $adb -s $dev shell settings get secure enabled_accessibility_services) | Select-Object -Last 1).Trim()
if ($actifs -notmatch [regex]::Escape($svc)) {
    $liste = if ($actifs -and $actifs -ne 'null') { "${actifs}:$svc" } else { $svc }
    & $adb -s $dev shell settings put secure enabled_accessibility_services $liste
    & $adb -s $dev shell settings put secure accessibility_enabled 1
}
"Balance Seul / Passagers : $((& $adb -s $dev shell settings get secure enabled_accessibility_services) | Select-Object -Last 1)"
$json = Join-Path $env:TEMP 'reglage-son.json'
[IO.File]::WriteAllText($json, '{"champSeul":[1,3]}')
& $adb -s $dev push $json /sdcard/Android/data/fr.p308.lanceur/files/reglage-son.json | Out-Null
'Position « Seul » envoyée (1 →, 3 ↑).'
if (-not $Captures) { "Fini en $([math]::Round(((Get-Date) - $t0).TotalSeconds)) s"; exit 0 }

# 3. Captures de la 3D (seulement avec -Captures)
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
