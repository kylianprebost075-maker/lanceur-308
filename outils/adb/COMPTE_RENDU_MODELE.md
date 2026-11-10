# Compte rendu de la séance ADB (à remplir par le Claude du Mac)

> Copier ce fichier en `journaux/COMPTE_RENDU.md` et remplir la copie. Ne pas modifier ce modèle.
> Écrire des faits observés. Quand quelque chose n'a pas été essayé ou n'a pas pu être vérifié, l'écrire.

## 1. Conditions
- Date et heure :
- Mac (modèle, version de macOS) :
- adb (résultat de `adb version`) :
- Câble utilisé (USB-A → USB-C ? marque, câble de données ou de charge ?) :
- Prise USB de l'autoradio utilisée (laquelle, s'il y en a plusieurs) :
- Moteur tournant ou contact seulement :

## 2. Ce qui a été fait sur l'autoradio AVANT de brancher (par l'utilisateur)
- Options pour les développeurs : activées ? comment (7 appuis sur « Numéro de build », code demandé ?) :
- Débogage USB : activé ? :
- Menu d'usine (code 3368) : ouvert ? Photos prises (où sont-elles) ? :
- Réglage changé dans le menu d'usine : lequel, valeur AVANT → valeur APRÈS (mot pour mot) :
- Autre réglage changé :

## 3. Liaison
- `adb devices -l` : (copier la ligne)
- L'autoradio a-t-il demandé d'autoriser l'ordinateur ? :
- Si rien n'est vu : ce que montre `journaux/<date>/A1_usb_du_mac.txt` (l'autoradio apparaît-il comme appareil USB ?) :
- Essai par le Wi-Fi (`adb connect <IP>:5555`) : fait ? résultat :

## 4. Relevé
- Commande lancée (avec ses options) :
- Dossier de résultats : `journaux/...`
- Copier ici le bloc « CE QUI COMPTE » de `RESUME.txt` :

```
```

- Fichiers vides ou en erreur (« Permission denied », « not found »…) :

## 5. Essais en plus (seulement avec l'accord de l'utilisateur)
| Essai | Fait ? | Commande exacte | Résultat mot pour mot |
|---|---|---|---|
| `adb root` | | | |
| `adb tcpip 5555` (ADB par le Wi-Fi pour la suite) | | | |
| `adb install -r Lanceur308.apk` | | | |
| Journal en direct pendant un lag de CarPlay (`--logcat`) | | | |
| Copie des applis du fabricant (`--apk`) | | | |

## 6. Ce qui a été remis comme avant
- Mode USB de l'autoradio remis sur « Host » ? :
- La clé USB et CarPlay remarchent-ils ? :
- Débogage laissé activé ou coupé ? :

## 7. Problèmes rencontrés, questions pour le Claude du PC
-

## 8. Ce qui n'a PAS été fait ou PAS vérifié
-
