#!/bin/bash
# Séance ADB du Lanceur 308 : relève l'état de l'autoradio depuis un Mac (ou Linux), EN LECTURE SEULE.
# Rien n'est modifié sur l'autoradio : pas de réglage changé, pas d'appli installée ou retirée, pas de root.
#
# Utilisation (dans ce dossier) :
#   bash seance_adb.sh                 relevé complet (2 à 4 minutes)
#   bash seance_adb.sh --logcat 120    en plus : journal en direct pendant 120 s (pour provoquer un lag de CarPlay)
#   bash seance_adb.sh --apk           en plus : copie des applis du fabricant (com.syu.*, com.fyt.*) pour étude
#
# Résultats : journaux/<date>/ (dossier ignoré par git : NE JAMAIS LE PUBLIER, il contient des numéros de série).
# Si adb n'est pas dans le PATH : ADB=/chemin/vers/adb bash seance_adb.sh

set -u
ADB="${ADB:-adb}"
LOGCAT_SEC=0
PULL_APK=0
while [ $# -gt 0 ]; do
  case "$1" in
    --logcat) LOGCAT_SEC="${2:-120}"; shift ;;
    --apk) PULL_APK=1 ;;
    *) echo "Option inconnue : $1"; exit 2 ;;
  esac
  shift
done

cd "$(dirname "$0")" || exit 1
OUT="journaux/$(date +%Y-%m-%d_%H-%M-%S)"
mkdir -p "$OUT"
RESUME="$OUT/RESUME.txt"

say() { echo "$*" | tee -a "$RESUME"; }

if ! command -v "$ADB" >/dev/null 2>&1; then
  say "ECHEC : adb introuvable. Sur Mac : brew install android-platform-tools (ou platform-tools de Google)."
  exit 1
fi

say "Séance ADB du $(date '+%d/%m/%Y %H:%M') — $("$ADB" version 2>&1 | head -n 1)"
"$ADB" start-server >/dev/null 2>&1
"$ADB" devices -l > "$OUT/A1_devices.txt" 2>&1
cat "$OUT/A1_devices.txt"

STATE="$("$ADB" get-state 2>&1)"
if [ "$STATE" != "device" ]; then
  say "ECHEC : aucun autoradio utilisable (état : $STATE)."
  say "  « unauthorized » : accepter la demande sur l'écran de l'autoradio."
  say "  rien dans la liste : prise USB de l'autoradio pas en mode « appareil », débogage pas activé, ou câble de charge seule."
  # Ce que le Mac voit sur ses prises USB : dit si l'autoradio est vu comme un appareil, même sans ADB.
  if command -v system_profiler >/dev/null 2>&1; then
    system_profiler SPUSBDataType > "$OUT/A1_usb_du_mac.txt" 2>&1
    say "  Liste des appareils USB vus par le Mac : $OUT/A1_usb_du_mac.txt"
  fi
  exit 1
fi

# sh <nom du fichier> <commande sur l'autoradio> : sortie et erreurs gardées, jamais bloquant sur une question.
sh_() {
  local name="$1"; shift
  printf '  %s\n' "$name"
  "$ADB" shell "$@" > "$OUT/$name.txt" 2>&1 < /dev/null
}
prop() { "$ADB" shell getprop "$1" 2>/dev/null < /dev/null | tr -d '\r'; }

echo "A. Liaison et droits"
sh_ A3_id id
sh_ A5_su 'which su; ls -l /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su 2>&1'

echo "B. Fiche du système"
sh_ B1_getprop getprop
sh_ B2_settings_global settings list global
sh_ B2_settings_system settings list system
sh_ B2_settings_secure settings list secure
sh_ B3_applis_systeme pm list packages -f -s
sh_ B3_applis_ajoutees pm list packages -f -3
sh_ B3_applis_desactivees pm list packages -d
sh_ B4_cpuinfo cat /proc/cpuinfo
sh_ B4_meminfo cat /proc/meminfo
sh_ B4_noyau cat /proc/version
sh_ B5_df df -h
sh_ B5_mounts cat /proc/mounts
sh_ B5_partitions 'ls -l /dev/block/by-name 2>&1; ls -l /dev/block/platform/*/by-name 2>&1'
sh_ B6_selinux getenforce
sh_ B7_ecran 'wm size; wm density'
sh_ B7_display dumpsys display

echo "C. Mémoire et processeur (pourquoi ça rame)"
sh_ C1_meminfo dumpsys meminfo
sh_ C2_top 'top -n 1 -b 2>&1 | head -n 45'
sh_ C2_cpuinfo dumpsys cpuinfo
sh_ C3_services dumpsys activity services
sh_ C3_processus dumpsys activity processes
sh_ C5_gfx_lanceur dumpsys gfxinfo fr.p308.lanceur
sh_ C5_gfx_carplay dumpsys gfxinfo com.syu.carlink
sh_ C6_thermique 'for z in /sys/class/thermal/thermal_zone*; do echo "$z $(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null)"; done; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>&1'
sh_ C8_dmesg dmesg

echo "D. Pour les fonctions du Lanceur"
sh_ D2_services_fyt dumpsys activity services com.syu.ms
sh_ D2_service_list service list
sh_ D3_audio dumpsys audio
sh_ D5_bluetooth dumpsys bluetooth_manager
sh_ D6_gps dumpsys location
sh_ D8_paquet_lanceur dumpsys package fr.p308.lanceur
printf '  %s\n' "D_logcat (journal déjà en mémoire)"
"$ADB" logcat -d -v time > "$OUT/D_logcat.txt" 2>&1 < /dev/null
printf '  %s\n' "D7_ecran.png"
"$ADB" exec-out screencap -p > "$OUT/D7_ecran.png" 2>/dev/null < /dev/null
printf '  %s\n' "fichiers du Lanceur (journal, fiche, plantages)"
"$ADB" pull /sdcard/Android/data/fr.p308.lanceur/files "$OUT/fichiers-lanceur" > "$OUT/D_pull_lanceur.txt" 2>&1 < /dev/null

if [ "$LOGCAT_SEC" -gt 0 ] 2>/dev/null; then
  echo "Journal en direct pendant $LOGCAT_SEC s : c'est le moment d'ouvrir CarPlay et de provoquer le lag…"
  "$ADB" logcat -c < /dev/null
  "$ADB" logcat -v time > "$OUT/C7_logcat_direct.txt" 2>&1 < /dev/null &
  LPID=$!
  sleep "$LOGCAT_SEC"
  kill "$LPID" 2>/dev/null
  sh_ C1_meminfo_apres dumpsys meminfo
  sh_ C2_top_apres 'top -n 1 -b 2>&1 | head -n 45'
fi

if [ "$PULL_APK" -eq 1 ]; then
  echo "Copie des applis du fabricant (pour étude sur le PC, jamais publiées)"
  mkdir -p "$OUT/apk-fyt"
  "$ADB" shell pm list packages -f < /dev/null 2>/dev/null | tr -d '\r' | grep -E '=(com\.syu\.|com\.fyt\.)' | while IFS= read -r line; do
    path="${line#package:}"; pkg="${path##*=}"; path="${path%=*}"
    printf '  %s\n' "$pkg"
    "$ADB" pull "$path" "$OUT/apk-fyt/$pkg.apk" > /dev/null 2>&1 < /dev/null
  done
fi

# Résumé : les réponses qui décident de la suite.
{
  echo
  echo "=== CE QUI COMPTE ==="
  echo "Modèle                : $(prop ro.product.model) / $(prop ro.product.device) / $(prop ro.board.platform)"
  echo "Android               : $(prop ro.build.version.release) (API $(prop ro.build.version.sdk)), correctif $(prop ro.build.version.security_patch)"
  echo "Version du système    : $(prop ro.build.display.id)"
  echo "Type de système       : $(prop ro.build.type) / $(prop ro.build.tags)   (user = fermé, userdebug = ouvert)"
  echo "ro.debuggable         : $(prop ro.debuggable)   (1 = « adb root » possible)"
  echo "ro.secure             : $(prop ro.secure)   (0 = ADB déjà administrateur)"
  echo "ro.adb.secure         : $(prop ro.adb.secure)"
  echo "Identité ADB          : $(tr -d '\r' < "$OUT/A3_id.txt" | head -n 1)"
  echo "Commande su           : $(tr -d '\r' < "$OUT/A5_su.txt" | head -n 1)"
  echo "SELinux               : $(tr -d '\r' < "$OUT/B6_selinux.txt" | head -n 1)"
  echo "Démarrage vérifié     : $(prop ro.boot.verifiedbootstate) / $(prop ro.boot.veritymode) / verrou $(prop ro.boot.flash.locked)"
  echo "ADB par le Wi-Fi      : port $(prop service.adb.tcp.port) / $(prop persist.adb.tcp.port)   (vide = non)"
  echo "Mode USB              : $(prop sys.usb.config) / $(prop persist.sys.usb.config)"
  echo "Écran                 : $(tr -d '\r' < "$OUT/B7_ecran.txt" | tr '\n' ' ')"
  echo "Mémoire               : $(grep -E 'MemTotal|MemAvailable' "$OUT/B4_meminfo.txt" | tr -d '\r' | tr -s ' ' | tr '\n' ' ')"
  echo "Lanceur installé      : $(grep -m 1 versionName "$OUT/D8_paquet_lanceur.txt" | tr -d '\r ')"
  echo "Applis système        : $(grep -c package: "$OUT/B3_applis_systeme.txt")   ajoutées : $(grep -c package: "$OUT/B3_applis_ajoutees.txt")   désactivées : $(grep -c package: "$OUT/B3_applis_desactivees.txt")"
  echo "Fichiers relevés      : $(ls "$OUT" | wc -l | tr -d ' ')"
} | tee -a "$RESUME"

echo
echo "Terminé. Résultats dans : $(pwd)/$OUT"
echo "Ne rien publier de ce dossier. Le rapporter tel quel (voir POUR_CLAUDE_MAC.md)."
