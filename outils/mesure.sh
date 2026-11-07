#!/bin/zsh
# Mesures de performance du Lanceur 308 sur l'émulateur $ANDROID_SERIAL (mode démo, page d'accueil 3D).
APK=$1
adb install -r "$APK" >/dev/null
adb shell pm grant fr.p308.lanceur android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb shell run-as fr.p308.lanceur sh -c 'mkdir -p shared_prefs; echo "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><boolean name=\"demo\" value=\"true\" /><int name=\"baseKm\" value=\"185300\" /></map>" > shared_prefs/vehicle.xml' 2>/dev/null
adb shell am force-stop fr.p308.lanceur; sleep 2
for i in 1 2 3; do
  adb shell am force-stop fr.p308.lanceur; sleep 2
  adb shell am start -W -n fr.p308.lanceur/.MainActivity | grep TotalTime
done
sleep 15
PID=$(adb shell pidof fr.p308.lanceur | tr -d '\r')
echo "CPU (%) sur 5 relevés de 2 s :"; for i in 1 2 3 4 5; do adb shell top -b -n 1 -d 2 -p $PID | awk -v p=$PID '$1==p {print $9}'; sleep 1; done | tr '\n' ' '; echo
adb shell dumpsys meminfo fr.p308.lanceur | grep -E "TOTAL|Graphics|Native Heap" | head -4
ls -la "$APK" | awk '{print "APK :", $5/1048576, "Mo"}'
