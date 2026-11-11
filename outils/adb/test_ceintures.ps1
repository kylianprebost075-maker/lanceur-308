<#
Test des ceintures (≈ 2 min) : l'autoradio transmet-il l'état des ceintures de sécurité par son boîtier CAN ?

À lancer contact mis (moteur coupé), PC et autoradio sur le même partage de connexion, « ADB par le Wi-Fi » activé.
Le script :
  1. lance l'écoute « Diagnostic CAN » du Lanceur sur l'autoradio (lecture seule, rien n'est envoyé à la voiture) ;
  2. annonce chaque geste à faire (texte + bip du PC), toutes les 10 s : boucler / déboucler les ceintures ;
  3. arrête l'écoute, récupère le rapport et cherche les canaux qui changent en même temps que les gestes.

Usage : .\test_ceintures.ps1                    (séance dans la voiture)
        .\test_ceintures.ps1 -Rapport x.txt -Gestes "14:13:50,14:14:00"   (analyse seule d'un rapport existant)
Le rapport et le résultat restent sur le PC (outils/adb/journaux/, jamais publié).
#>
param([string]$Rapport = '', [string]$Gestes = '', [int]$Pas = 10)
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path $PSScriptRoot "journaux\ceintures-$(Get-Date -Format 'yyyyMMdd-HHmm')"

# Canaux qui bougent tout seuls (relevés du 11/11 : tension, deux valeurs inconnues, coordonnées du toucher).
$bruit = @('0:114', '0:115', '0:146', '0:77')

function Analyser([string]$fichier, [datetime[]]$instants) {
    $lignes = Get-Content $fichier -Encoding UTF8
    $chg = foreach ($l in $lignes) {
        $m = [regex]::Match($l, '^(\d\d:\d\d:\d\d)\s+FYT module (\d+) canal (\d+) : (.*?) → (.*)$')
        if ($m.Success) {
            [pscustomobject]@{ T = [datetime]::ParseExact($m.Groups[1].Value, 'HH:mm:ss', $null)
                Cle = "$($m.Groups[2].Value):$($m.Groups[3].Value)"; De = $m.Groups[4].Value; Vers = $m.Groups[5].Value }
        }
    }
    $chg = @($chg | Where-Object { $bruit -notcontains $_.Cle })
    "Changements hors canaux bruyants : $($chg.Count)"
    if ($chg.Count -eq 0) { "=> AUCUN canal n'a bougé : le boîtier CAN ne transmet pas les ceintures."; return }
    $res = $chg | Group-Object Cle | ForEach-Object {
        $dans = @($_.Group | Where-Object { $t = $_.T; @($instants | Where-Object { [math]::Abs(($t - $_).TotalSeconds) -le 4 }).Count -gt 0 }).Count
        [pscustomobject]@{ Canal = "module $($_.Name.Split(':')[0]) code $($_.Name.Split(':')[1])"; Changements = $_.Count
            AuBonMoment = $dans; Valeurs = (($_.Group | ForEach-Object { $_.Vers }) | Select-Object -Unique) -join ' / ' }
    } | Sort-Object AuBonMoment, Changements -Descending
    $res | Format-Table -AutoSize | Out-String -Width 200
    $bon = $res | Where-Object { $_.AuBonMoment -ge [math]::Max(2, [math]::Floor($instants.Count / 2)) -and $_.AuBonMoment -ge $_.Changements * 0.7 }
    if ($bon) { "=> Canal(aux) qui suivent les ceintures : $(($bon | ForEach-Object { $_.Canal }) -join ', ')" }
    else { "=> Rien ne suit clairement les gestes : ceintures probablement non transmises (ou gestes ratés)." }
}

if ($Rapport) {
    $inst = $Gestes.Split(',') | ForEach-Object { [datetime]::ParseExact($_.Trim(), 'HH:mm:ss', $null) }
    Analyser $Rapport $inst
    return
}

# --- Séance dans la voiture ---
$w = Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'Wi-Fi' -ErrorAction SilentlyContinue | Select-Object -First 1
$base = ($w.IPAddress -split '\.')[0..2] -join '.'
$tries = @{}; 1..254 | ForEach-Object { $c = New-Object Net.Sockets.TcpClient; $tries["$base.$_"] = @($c, $c.ConnectAsync("$base.$_", 5555)) }
Start-Sleep 2
$ip = $tries.GetEnumerator() | Where-Object { $_.Value[1].IsCompleted -and $_.Value[0].Connected } | Select-Object -First 1 -ExpandProperty Key
$tries.Values | ForEach-Object { $_[0].Close() }
if (-not $ip) { 'Autoradio introuvable : ADB par le Wi-Fi activé ? même partage de connexion ?'; exit 1 }
$dev = "${ip}:5555"; & $adb connect $dev | Out-Null
"Autoradio : $dev"

function Toucher([string]$texte) {
    for ($i = 0; $i -lt 3; $i++) {
        & $adb -s $dev shell uiautomator dump /sdcard/ui.xml | Out-Null
        $x = [Text.Encoding]::UTF8.GetString([Text.Encoding]::Default.GetBytes(((& $adb -s $dev exec-out cat /sdcard/ui.xml) -join "`n")))
        $m = [regex]::Match($x, 'text="' + [regex]::Escape($texte) + '[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($m.Success) {
            $g = $m.Groups; & $adb -s $dev shell input tap ([int](([int]$g[1].Value + [int]$g[3].Value) / 2)) ([int](([int]$g[2].Value + [int]$g[4].Value) / 2))
            Start-Sleep 2; return $true
        }
        Start-Sleep 1
    }
    return $false
}

& $adb -s $dev shell am start -n fr.p308.lanceur/.MainActivity | Out-Null; Start-Sleep 2
if (-not (Toucher 'Véhicule')) { & $adb -s $dev shell input tap 46 165; Start-Sleep 2 }  # icône sans texte : position connue
if (-not (Toucher 'Diagnostic CAN')) { 'Onglet Diagnostic CAN introuvable'; exit 1 }
if (-not (Toucher 'Démarrer')) { 'Bouton Démarrer introuvable'; exit 1 }

# Décalage entre l'horloge de l'autoradio (heures du rapport) et celle du PC.
$radio = [datetime]::ParseExact(((& $adb -s $dev shell date '+%H:%M:%S') | Select-Object -Last 1).Trim(), 'HH:mm:ss', $null)
$decalage = $radio - [datetime]::ParseExact((Get-Date -Format 'HH:mm:ss'), 'HH:mm:ss', $null)

$etapes = @(
    'BOUCLE la ceinture du PASSAGER AVANT', 'DÉBOUCLE la ceinture du passager avant',
    'BOUCLE la ceinture du passager avant', 'DÉBOUCLE la ceinture du passager avant',
    'BOUCLE une ceinture ARRIÈRE (derrière toi)', 'DÉBOUCLE cette ceinture arrière',
    'BOUCLE ta PROPRE ceinture (conducteur)', 'DÉBOUCLE ta ceinture')
"`nÉcoute lancée. Un geste toutes les $Pas s, au bip. Reste immobile entre deux gestes.`n"
Start-Sleep 5
$instants = @()
$n = 0
foreach ($e in $etapes) {
    $n++
    "[$n/$($etapes.Count)] $e  (dans 3 s)"
    Start-Sleep 3
    [console]::Beep(1200, 250)
    "     >>> MAINTENANT"
    $instants += (Get-Date).Add($decalage)
    Start-Sleep ($Pas - 3)
}
"`nFin des gestes. Arrêt de l'écoute…"
Toucher 'Terminer' | Out-Null
Start-Sleep 3
New-Item -ItemType Directory -Force $out | Out-Null
& $adb -s $dev pull /sdcard/Android/data/fr.p308.lanceur/files/diagnostic-can.txt "$out\diagnostic-can.txt" | Out-Null
($instants | ForEach-Object { $_.ToString('HH:mm:ss') }) -join ',' | Out-File "$out\gestes.txt" -Encoding utf8
"Rapport : $out"
$res = Analyser "$out\diagnostic-can.txt" $instants
$res
$res | Out-File "$out\resultat.txt" -Encoding utf8
