# Essai du micro de l'autoradio (v0.35) : peut-on distinguer « quelqu'un parle » de « quelqu'un chante » par-dessus la
# musique ? Le PC installe le Lanceur, lui donne le micro le temps de l'essai, enregistre pendant que tu fais 4 gestes,
# puis récupère le son et retire l'autorisation. Tout va à TON rythme : rien n'avance sans la touche Entrée.
# Avant : PC et autoradio sur le même partage de connexion, « ADB par le Wi-Fi » activé dans les Réglages du Lanceur,
# ta musique lancée comme d'habitude (CarPlay), au volume habituel.
# Le son reste sur ce PC (outils/son/mesures/micro, jamais publié) et il est effacé de l'autoradio.
param([string]$Appareil)
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$out = "$root\outils\son\mesures\micro"
$pkg = 'fr.p308.lanceur'
$dir = "/sdcard/Android/data/$pkg/files"
$rcv = "$pkg/.sound.MicTestReceiver"
New-Item -ItemType Directory -Force $out | Out-Null

# 1. Autoradio + installation (script habituel), sauf si -Appareil est donné (émulateur).
if ($Appareil) { $dev = $Appareil } else {
    & "$PSScriptRoot\voiture_rapide.ps1"
    $dev = ((& $adb devices) -match ':5555\s+device' | Select-Object -First 1) -replace '\s+device', ''
}
if (-not $dev) { 'Autoradio introuvable : essai annulé.'; exit 1 }
$version = ((& $adb -s $dev shell dumpsys package $pkg) -match 'versionName' | Select-Object -First 1)
"Lanceur sur l'autoradio : $version"

# 2. Micro : autorisation donnée par le PC, puis première source qui marche (1 micro, 6 reconnaissance vocale,
#    7 appel, 5 caméscope). L'autoradio peut refuser pendant CarPlay : c'est justement ce qu'on veut savoir.
& $adb -s $dev shell pm grant $pkg android.permission.RECORD_AUDIO
function Etat { ((& $adb -s $dev shell cat "$dir/essai-micro.txt" 2>$null) | Select-Object -Last 1) }
''
Read-Host 'Lance ta musique comme d''habitude, au volume habituel, puis appuie sur Entrée'
$ok = $false
foreach ($source in 1, 6, 7, 5) {
    & $adb -s $dev shell rm -f "$dir/essai-micro.txt" "$dir/essai-micro.wav"
    & $adb -s $dev shell am broadcast -n $rcv -a fr.p308.lanceur.MIC_START --ei source $source | Out-Null
    Start-Sleep 3
    $e = Etat
    "  source $source : $e"
    if ($e -match '^ENREGISTRE') { $ok = $true; break }
    & $adb -s $dev shell am broadcast -n $rcv -a fr.p308.lanceur.MIC_STOP | Out-Null
    Start-Sleep 1
}
$t0 = Get-Date
$phases = @()
if (-not $ok) {
    "Le micro est refusé avec la musique en cours. C'est un résultat : la fonction ne pourra pas marcher ainsi."
} else {
    # 3. Les gestes, un par un. Entrée pour commencer, Entrée quand c'est fini (15 secondes environ suffisent).
    $gestes = @(
        @('musique', 'Musique seule : personne ne parle'),
        @('parle', 'Tu PARLES normalement, comme à un passager (raconte ta journée)'),
        @('chante', 'Tu CHANTES sur la musique'),
        @('silence', 'Mets la musique en PAUSE et parle encore (référence), puis relance-la')
    )
    foreach ($g in $gestes) {
        ''
        Read-Host "PROCHAIN GESTE : $($g[1]). Entrée pour commencer"
        $a = ((Get-Date) - $t0).TotalSeconds + 3
        Read-Host '   ... en cours. Entrée quand c''est fini'
        $b = ((Get-Date) - $t0).TotalSeconds + 3
        $phases += [pscustomobject]@{ geste = $g[0]; debut = [math]::Round($a, 1); fin = [math]::Round($b, 1) }
    }
}

# 4. Fin : arrêt, récupération, nettoyage de l'autoradio, autorisation retirée.
& $adb -s $dev shell am broadcast -n $rcv -a fr.p308.lanceur.MIC_STOP | Out-Null
Start-Sleep 4
$fin = Etat
"Fin : $fin"
$nom = 'essai-micro-' + (Get-Date -Format 'yyyyMMdd-HHmm')
if ($ok) { & $adb -s $dev pull "$dir/essai-micro.wav" "$out\$nom.wav" | Select-Object -Last 1 }
$rapport = @("version: $version", "etat: $fin", 'geste;debut_s;fin_s') + ($phases | ForEach-Object { "$($_.geste);$($_.debut);$($_.fin)" })
[IO.File]::WriteAllLines("$out\$nom.txt", $rapport)
& $adb -s $dev shell rm -f "$dir/essai-micro.txt" "$dir/essai-micro.wav"
& $adb -s $dev shell pm revoke $pkg android.permission.RECORD_AUDIO
Start-Sleep 2
& $adb -s $dev shell am start -n "$pkg/.MainActivity" | Out-Null
"Fini. Fichiers : $out\$nom.wav et .txt. Micro retiré au Lanceur."
