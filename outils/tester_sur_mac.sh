#!/bin/zsh
# Ouvre un autoradio virtuel (écran 1024×600) sur le Mac et y lance le Lanceur 308.
# Usage : ~/autoradio-308/tester_sur_mac.sh
SDK=$(brew --prefix)/share/android-commandlinetools
APK=~/autoradio-308/Lanceur308.apk
export ANDROID_SERIAL=emulator-5554

if ! adb devices | grep -q "^emulator-5554"; then
  echo "Démarrage de l'autoradio virtuel…"
  nohup $SDK/emulator/emulator -avd autoradio-android8 -memory 4096 -cores 4 -gpu host -no-audio -no-boot-anim -no-snapshot-save \
    > ~/autoradio-308/logs/emulator.log 2>&1 &
fi
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done

echo "Installation de l'appli…"
adb install -r "$APK" > /dev/null
adb shell pm grant fr.p308.lanceur android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb shell pm grant fr.p308.lanceur android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell pm grant fr.p308.lanceur android.permission.BLUETOOTH_CONNECT 2>/dev/null
adb shell appops set fr.p308.lanceur SYSTEM_ALERT_WINDOW allow
adb shell am start -n fr.p308.lanceur/.MainActivity > /dev/null
echo "C'est parti : la fenêtre de l'émulateur montre l'appli (mode démo dans Réglages)."
