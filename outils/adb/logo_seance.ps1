<#
Séance « logo Peugeot au démarrage » (préparée le 11/11/2026). LECTURE SEULE : le script ne change rien lui-même.

À lancer contact mis, PC et autoradio sur le même partage de connexion, « ADB par le Wi-Fi » activé.
  1. relève ce que l'autoradio sait du logo : propriétés (persist.sys.carmarkbox = logo_001 le 11/11, empreinte
     persist.lsec.cp.logo_md5), fichiers de logos et d'animation de démarrage visibles sans root ;
  2. à ton rythme : tu ouvres sur l'autoradio le menu où se trouve le logo (Réglages → réglages d'usine, code 3368),
     puis Entrée sur le PC : capture d'écran et liste des textes de l'écran, autant de fois que tu veux (« f » = fini).
Tout reste sur le PC dans outils/adb/journaux/logo-<date>/ (jamais publié). Le choix du logo se fait ensuite, ensemble :
ne toucher QUE la ligne du logo (le même menu règle le boîtier CAN et le modèle de voiture : ne pas y toucher).
#>
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path $PSScriptRoot "journaux\logo-$(Get-Date -Format 'yyyyMMdd-HHmm')"
New-Item -ItemType Directory -Force $out | Out-Null

# Autoradio sur le réseau du PC (port 5555).
$w = Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'Wi-Fi' -ErrorAction SilentlyContinue | Select-Object -First 1
$base = ($w.IPAddress -split '\.')[0..2] -join '.'
$tries = @{}; 1..254 | ForEach-Object { $c = New-Object Net.Sockets.TcpClient; $tries["$base.$_"] = @($c, $c.ConnectAsync("$base.$_", 5555)) }
Start-Sleep 2
$ip = $tries.GetEnumerator() | Where-Object { $_.Value[1].IsCompleted -and $_.Value[0].Connected } | Select-Object -First 1 -ExpandProperty Key
$tries.Values | ForEach-Object { $_[0].Close() }
if (-not $ip) { 'Autoradio introuvable : ADB par le Wi-Fi activé ? même partage de connexion ?'; exit 1 }
$dev = "${ip}:5555"; & $adb connect $dev | Out-Null
"Autoradio : $dev"

# 1. Ce que l'autoradio sait du logo (lecture seule).
"Relevé du logo…"
$props = (& $adb -s $dev shell getprop) | Where-Object { $_ -match 'logo|carmark|bootanim|boot_logo|splash' }
$fichiers = & $adb -s $dev shell "ls -la /system/media /oem/media /oem /system/etc/logo* /data/media/0/logo* 2>/dev/null; find /system /oem /vendor -maxdepth 4 \( -iname '*logo*' -o -iname 'bootanimation*' \) 2>/dev/null | head -n 80"
$applis = (& $adb -s $dev shell pm list packages) | Where-Object { $_ -match 'logo|setting|syu|fyt' }
@('== Propriétés ==') + $props + @('', '== Fichiers ==') + $fichiers + @('', '== Applis réglages FYT ==') + $applis |
    Out-File "$out\releve-logo.txt" -Encoding utf8
$props

# 2. Captures à ton rythme.
"`nSur l'autoradio, ouvre le menu où se règle le logo (Réglages → réglages d'usine, code 3368)."
"À chaque écran utile : Entrée ici (capture + textes). Tape f puis Entrée pour finir. Ne change rien pour l'instant."
$n = 0
while ($true) {
    $r = Read-Host "Entrée = capture, f = fini"
    if ($r -eq 'f') { break }
    $n++
    $png = "$out\ecran-$n.png"
    cmd /c "`"$adb`" -s $dev exec-out screencap -p > `"$png`""
    & $adb -s $dev shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
    $x = [Text.Encoding]::UTF8.GetString([Text.Encoding]::Default.GetBytes(((& $adb -s $dev exec-out cat /sdcard/ui.xml) -join "`n")))
    $textes = [regex]::Matches($x, 'text="([^"]+)"[^>]*bounds="([^"]+)"') | ForEach-Object { "$($_.Groups[1].Value)  $($_.Groups[2].Value)" }
    $textes | Out-File "$out\ecran-$n.txt" -Encoding utf8
    "  capture $n ($($textes.Count) textes lus)"
}
"Fini : $n capture(s) dans $out"
