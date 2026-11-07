#!/bin/zsh
# Enregistre les journaux Android liés au GPS, à la localisation et à CarPlay/ZLink pendant un trajet.
# Nécessite ADB : sous Android 8, active « Débogage USB » puis : ~/autoradio-308/gps_log.sh <IP de l'autoradio>
# Arrêt : Ctrl+C. Les fichiers vont dans ~/autoradio-308/logs/.
IP=${1:-}
OUT=~/autoradio-308/logs/trajet-$(date +%Y%m%d-%H%M%S)
if ! adb devices | grep -q 'device$'; then
  [ -n "$IP" ] || { echo "Usage : $0 <IP de l'autoradio>"; exit 1; }
  adb connect "$IP:5555" | grep -q connected || { echo "Connexion ADB refusée sur $IP:5555 : utilise plutôt le journal intégré au Lanceur 308."; exit 1; }
fi
echo "Enregistrement dans $OUT-logcat.txt (Ctrl+C pour arrêter)"
adb logcat -c
adb logcat -v time | grep --line-buffered -iE "gps|gnss|location|nmea|carplay|zlink|iap|autokit|carbit" > "$OUT-logcat.txt" &
LP=$!
trap "kill $LP 2>/dev/null; echo; echo 'Terminé : envoie-moi les fichiers $OUT-*'; exit 0" INT
while true; do
  { echo "=== $(date +%T)"; adb shell dumpsys location | grep -iE "Location\[gps|satellites|Last Known" | head -5; } >> "$OUT-etat-gps.txt"
  sleep 30
done
