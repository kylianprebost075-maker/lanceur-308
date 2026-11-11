"""Égaliseur 15 bandes de l'autoradio FYT (appli com.syu.eq, onglet DSP) lu et réglé depuis le PC par ADB.

Relevé sur l'autoradio le 11/11/2026 (écran 1280 × 720) : 15 points sur une courbe, de 25 Hz à 16 kHz, de +12 dB
(en haut) à -12 dB (en bas). On lit la position de chaque point sur une capture d'écran, et on le déplace en le
faisant glisser (`adb shell input swipe`). Aucune autre commande n'est envoyée à l'autoradio.
"""
from __future__ import annotations

import io
import subprocess

from PIL import Image

from mesure_son import FYT15

# Abscisse de chaque bande et ordonnées de +12 / 0 / -12 dB (pixels, écran 1280 × 720).
X = [128, 201, 275, 347, 420, 493, 566, 639, 711, 784, 857, 930, 1003, 1076, 1149]
Y_HAUT, Y_ZERO, Y_BAS = 231, 400, 567
PX_PAR_DB = (Y_BAS - Y_HAUT) / 24


def db_vers_y(db: float) -> int:
    return round(Y_ZERO - max(-12.0, min(12.0, db)) * PX_PAR_DB)


def y_vers_db(y: float) -> float:
    return (Y_ZERO - y) / PX_PAR_DB


def lire_points(img: Image.Image) -> dict[float, float]:
    """Valeur (dB, arrondie) de chaque bande : le point est la tache la plus claire de sa colonne."""
    px = img.convert("RGB").load()
    out = {}
    for b, x in zip(FYT15, X):
        meilleur, ym = -1, Y_ZERO
        for y in range(Y_HAUT - 4, Y_BAS + 5):
            s = 0
            for dx in (-2, 0, 2):
                for dy in (-2, 0, 2):
                    r, g, bl = px[x + dx, y + dy]
                    s += r + g + bl
            if s > meilleur:
                meilleur, ym = s, y
        out[b] = round(y_vers_db(ym))
    return out


class Adb:
    def __init__(self, adb: str, dev: str):
        self.adb, self.dev = adb, dev

    def sh(self, *args: str) -> str:
        # UTF-8 explicite : l'écran contient des accents (« montée », « Arrêter ») ; Windows décoderait en cp1252.
        r = subprocess.run([self.adb, "-s", self.dev, "shell", *args], capture_output=True)
        return r.stdout.decode("utf-8", errors="replace")

    def capture(self) -> Image.Image:
        png = subprocess.run([self.adb, "-s", self.dev, "exec-out", "screencap", "-p"], capture_output=True).stdout
        return Image.open(io.BytesIO(png))


def ouvrir_eq(a: Adb):
    """Ouvre l'égaliseur du fabricant sur l'onglet DSP (15 bandes)."""
    import time
    a.sh("monkey", "-p", "com.syu.eq", "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(3)
    a.sh("input", "tap", "223", "680")  # onglet DSP
    time.sleep(1.5)


def regler(a: Adb, cible: dict[float, float], essais: int = 2) -> dict[float, float]:
    """Fait glisser chaque point vers sa valeur cible ; renvoie les valeurs relues à la fin."""
    import time
    for _ in range(essais):
        lu = lire_points(a.capture())
        a_faire = [(b, x) for b, x in zip(FYT15, X) if abs(lu[b] - cible.get(b, 0.0)) >= 1]
        if not a_faire:
            return lu
        for b, x in a_faire:
            a.sh("input", "swipe", str(x), str(db_vers_y(lu[b])), str(x), str(db_vers_y(cible.get(b, 0.0))), "400")
            time.sleep(0.4)
        time.sleep(1)
    return lire_points(a.capture())
