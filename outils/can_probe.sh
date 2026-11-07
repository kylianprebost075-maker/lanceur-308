#!/bin/zsh
# Repérage du CAN de l'autoradio — LECTURE SEULE, ne modifie rien.
# Usage : ./can_probe.sh   (autoradio connecté en ADB, contact mis)
set -u
O=~/autoradio-308/can/$(date +%Y%m%d-%H%M%S); mkdir -p $O
adb devices | grep -q 'device$' || { echo "Autoradio non connecté (adb connect IP:PORT)"; exit 1; }

echo "1/4 Plateforme…"
adb shell getprop | grep -iE "platform|board|hardware|product|fyt|syu|mcu|can|customer|ui_ver|version" > $O/props.txt

echo "2/4 Applis liées au CAN / véhicule…"
adb shell pm list packages -f | grep -iE "can|car|syu|fyt|microntek|hct|ts\.|hiworld|raise|xinpu|binary|simple|vehicle|mcu|backcar|radar" > $O/can_packages.txt
cut -d= -f2- $O/can_packages.txt | tr -d '\r' | while read -r p; do
  adb shell dumpsys package "$p" | grep -E "Action:|Service|Provider|Receiver|authority|android.intent" | sort -u > "$O/pkg_$p.txt"
done

echo "3/4 Services système non standard…"
adb shell service list | grep -viE "android\.|com\.android|media\.|SurfaceFlinger" > $O/services.txt

echo "4/4 Enregistrement de 60 s : OUVRE puis FERME la porte conducteur, puis le coffre, puis passe la marche arrière (frein à main serré)."
adb logcat -c
adb logcat -v time > $O/logcat_portes.txt &
LP=$!
for i in {60..1}; do printf "\r  %2d s " $i; sleep 1; done; echo
kill $LP 2>/dev/null
echo "Terminé : $O"
