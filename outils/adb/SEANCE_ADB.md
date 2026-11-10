# Séance ADB : liste de ce qu'il faut récupérer et tester

À suivre le jour où le PC est relié à l'autoradio (câble USB-A → USB-C ou Wi-Fi), **après** avoir activé le
débogage (voir README §1 « Accès à l'autoradio », menu d'usine code `3368`).

Outil sur le PC : `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (déjà installé avec le SDK Android).

## Règles de la séance

- **Lecture d'abord.** Les parties A à D ne modifient rien sur l'autoradio.
- Tout ce qui est récupéré va dans `outils/adb/journaux/` : ce dossier est ignoré par git, **rien n'est publié**
  (numéros de série, adresses, applis du fabricant).
- Jamais de désinstallation d'appli système (`com.syu.*`, `com.fyt.*`), jamais d'écriture dans `/system`,
  jamais de `adb reboot bootloader`, de flash ni de `adb disable-verity`.
- Chaque réglage changé en partie E est noté avec sa valeur d'avant, pour pouvoir revenir en arrière.
- Voiture à l'arrêt, contact mis, moteur de préférence tournant (batterie à 9,4 V au démarreur le 09/11).

## A. La liaison marche-t-elle ? (2 min)

| # | À tester | Commande | Ce qu'on attend |
|---|---|---|---|
| A1 | L'autoradio est vu | `adb devices -l` | une ligne `device` (si `unauthorized` : accepter sur l'écran) |
| A2 | Par le Wi-Fi | `adb connect <adresse IP>:5555` | `connected` (fermé lors du dernier essai) |
| A3 | Qui on est | `adb shell id` | `uid=2000(shell)` ; `uid=0(root)` serait une bonne surprise |
| A4 | Droits administrateur | `adb root` puis `adb shell id` | refus probable sur un système `user` ; accepté si `ro.debuggable=1` |
| A5 | Commande su | `adb shell which su` et `adb shell su -c id` | présente d'origine sur certains FYT |

## B. Fiche du système à récupérer (5 min)

| # | Quoi | Commande |
|---|---|---|
| B1 | Toutes les propriétés | `adb shell getprop` |
| B2 | Réglages Android | `adb shell settings list global`, `... list system`, `... list secure` |
| B3 | Applis installées avec leur fichier | `adb shell pm list packages -f -s` (système), `-3` (ajoutées), `-d` (désactivées) |
| B4 | Processeur, mémoire, noyau | `adb shell cat /proc/cpuinfo /proc/meminfo /proc/version` |
| B5 | Stockage et partitions | `adb shell df -h`, `adb shell cat /proc/mounts`, `adb shell ls -l /dev/block/by-name` |
| B6 | SELinux | `adb shell getenforce` |
| B7 | Écran | `adb shell wm size`, `adb shell wm density`, `adb shell dumpsys display` |
| B8 | Rapport complet | `adb bugreport` (gros fichier, long ; en dernier) |

À comparer avec `infos-autoradio.txt` écrit par le Lanceur v0.19 : ADB voit plus de choses qu'une appli.

## C. Pourquoi ça rame (mémoire, CarPlay) (10 min)

| # | Quoi | Commande | Quand |
|---|---|---|---|
| C1 | Qui prend la mémoire | `adb shell dumpsys meminfo` | au repos, puis CarPlay ouvert |
| C2 | Qui prend le processeur | `adb shell top -n 1 -m 25` et `adb shell dumpsys cpuinfo` | au repos, puis CarPlay ouvert |
| C3 | Ce qui tourne en fond | `adb shell dumpsys activity services` et `... activity processes` | une fois |
| C4 | Applis lancées au démarrage | `adb shell dumpsys package` (récepteurs `BOOT_COMPLETED`) | une fois |
| C5 | Fluidité de l'affichage | `adb shell dumpsys gfxinfo fr.p308.lanceur` puis `com.syu.carlink` | pendant un lag |
| C6 | Température, fréquence du processeur | `adb shell cat /sys/class/thermal/thermal_zone*/temp`, `.../cpufreq/scaling_cur_freq` | pendant un lag |
| C7 | Journal en direct pendant un gel CarPlay | `adb logcat -v time` (enregistré 2 min) | provoquer le lag |
| C8 | Journal du noyau | `adb shell dmesg` | une fois (peut être refusé) |

## D. Pour les fonctions du Lanceur (15 min)

| # | Quoi | Commande | À quoi ça sert |
|---|---|---|---|
| D1 | Journal du CAN FYT | `adb logcat` filtré sur `com.syu.ms` / `canbus` | trouver les codes des phares, antibrouillards, température extérieure : actionner chaque commande une par une et noter l'heure |
| D2 | Services FYT | `adb shell dumpsys activity services com.syu.ms` et `adb shell service list` | modules et canaux qu'on ne connaît pas encore |
| D3 | Son / DSP | `adb shell dumpsys audio`, `adb shell dumpsys media.audio_flinger` | piloter le DSP (alignement, égaliseur) |
| D4 | Copie des applis FYT | `adb pull` des fichiers listés en B3 (`com.syu.ms`, `com.syu.carlink`, `com.syu.canbus`, réglages, DSP) | étude sur le PC (jamais publiées) |
| D5 | Bluetooth | `adb shell dumpsys bluetooth_manager` | coupures du boîtier OBD au démarreur |
| D6 | GPS | `adb shell dumpsys location` | gels du GPS sous CarPlay |
| D7 | Capture et vidéo de l'écran | `adb exec-out screencap -p`, `adb shell screenrecord` | captures réelles pour le README (plaque masquée) |
| D8 | Journal du Lanceur | `adb logcat --pid` du Lanceur | plantages éventuels |
| D9 | Installer sans la clé | `adb install -r Lanceur308.apk` | mises à jour depuis le PC (essayer une fois) |

## E. Réglages à essayer ensuite (un par un, réversibles, avec ton accord)

À ne faire qu'après lecture des parties B et C. Aucun ne touche au système de façon définitive.

| # | Essai | Retour en arrière |
|---|---|---|
| E1 | Animations à 0,5 ou 0 (`settings put global window_animation_scale` …) | remettre 1 |
| E2 | Limiter les applis en arrière-plan | remettre la valeur notée |
| E3 | Donner au Lanceur les droits `WRITE_SECURE_SETTINGS` et `DUMP` (`pm grant`) : réglages faits tout seuls, mesures plus fines | `pm revoke` |
| E4 | Désactiver **pour l'utilisateur** les applis ajoutées inutiles (`pm disable-user`), jamais les `com.syu.*` / `com.fyt.*` | `pm enable` |
| E5 | Sortir le Lanceur des économies d'énergie (`dumpsys deviceidle whitelist +fr.p308.lanceur`) | même commande avec `-` |
| E6 | ADB par le Wi-Fi pour la suite (`adb tcpip 5555`) | redémarrage de l'autoradio |

## Ce qu'on ne fera pas

Root par Magisk, flash d'un autre système, changement du boîtier CAN ou du modèle de voiture dans le menu
d'usine, suppression d'applis du fabricant : risque de bloquer l'autoradio, sans moyen simple de le réparer.

## Pour reprendre

Un script qui lance les parties A à D d'un coup et range les résultats dans `outils/adb/journaux/` reste à
écrire, une fois la partie A réussie (inutile avant de savoir si la liaison se fait).
