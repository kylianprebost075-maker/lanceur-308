"""Réglage automatique du son de la voiture : le PC pilote l'autoradio par ADB (Wi-Fi), écoute au micro et règle.

PC posé à la place du conducteur, micro à hauteur des oreilles, portes fermées, moteur coupé (contact mis).
L'autoradio et le PC sont sur le même partage de connexion, « ADB par le Wi-Fi » activé dans les Réglages du Lanceur.

  1. bruit de fond, puis bruit rose joué par le Lanceur → courbe de réponse « avant »
  2. montée lente des basses (30 → 150 Hz) à volume fort → fréquence où les portes vibrent (grésillement au micro)
  3. égaliseur 15 bandes de l'autoradio réglé d'après la courbe (glisser des points, valeurs d'avant notées)
  4. fréquence de vibration envoyée au Lanceur (basses automatiques activées)
  5. bruit rose à nouveau → courbe « après » ; rapport et graphique dans le dossier « mesures » (jamais publié)

Usage : python reglage_auto.py [--micro N] [--appareil IP:5555] [--sans-eq] [--sans-vibration] [--deux-places]
Avec --deux-places, la courbe est aussi mesurée à la place passager avant (on déplace le PC quand il le dit) et
l'égaliseur vise le meilleur compromis entre les deux places (l'égaliseur de l'autoradio est commun).
Rien n'est envoyé à la voiture : seulement à l'autoradio (sons de test, égaliseur, réglage du Lanceur).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import mesure_son as m
import fyt_eq

ADB = str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe")
FICHIERS_LANCEUR = "/sdcard/Android/data/fr.p308.lanceur/files"


# --------------------------------------------------------------------------------------------- autoradio

def trouver_autoradio() -> str | None:
    """Premier appareil du réseau Wi-Fi du PC dont le port ADB (5555) répond."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()
    base = ip.rsplit(".", 1)[0]

    def essai(k):
        c = socket.socket()
        c.settimeout(0.6)
        try:
            return f"{base}.{k}" if c.connect_ex((f"{base}.{k}", 5555)) == 0 else None
        finally:
            c.close()

    with ThreadPoolExecutor(64) as ex:
        for r in ex.map(essai, [k for k in range(1, 255) if f"{base}.{k}" != ip]):
            if r:
                return f"{r}:5555"
    return None


class Autoradio(fyt_eq.Adb):
    def dump(self) -> str:
        self.sh("uiautomator", "dump", "/sdcard/ui.xml")
        return self.sh("cat", "/sdcard/ui.xml")

    def toucher_texte(self, texte: str, essais: int = 3) -> bool:
        """Touche l'élément dont le texte commence par `texte` (lu dans l'arbre de l'écran)."""
        for _ in range(essais):
            mt = re.search(r'text="' + re.escape(texte) + r'[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', self.dump())
            if mt:
                x1, y1, x2, y2 = map(int, mt.groups())
                self.sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(1.5)
                return True
            time.sleep(1)
        return False

    def onglet_son(self, sous_onglet: str):
        self.sh("am", "start", "-n", "fr.p308.lanceur/.MainActivity")
        time.sleep(2)
        if not self.toucher_texte("Son"):
            raise SystemExit("Onglet Son du Lanceur introuvable à l'écran.")
        if not self.toucher_texte(sous_onglet):
            raise SystemExit(f"Sous-onglet « {sous_onglet} » introuvable.")


# --------------------------------------------------------------------------------------------- étapes

SANS_QUESTION = False


def demander(msg: str):
    if SANS_QUESTION:
        print(f"\n>> {msg}")
        return
    input(f"\n>> {msg}\n   Entrée pour continuer. ")


def mesurer_courbe(a: Autoradio, micro, fond: dict) -> tuple[dict, dict, object]:
    a.onglet_son("Mesure au PC")
    if not a.toucher_texte("Bruit rose les deux"):
        raise SystemExit("Bouton « Bruit rose les deux » introuvable.")
    x = m.enregistrer(14, micro)
    a.toucher_texte("Arrêter")
    m.sauver_wav(x, "auto-rose")
    x = x[m.RATE:]
    niv = m.niveaux_tiers(x)
    fiable = {f: niv[f] - fond[f] >= 10 for f in m.TIERS}
    return m.ecarts(niv), fiable, x


#: Poids des places dans le réglage commun (--deux-places) : le conducteur est toujours là, le passager pas toujours.
POIDS_PLACES = {"conducteur": 0.6, "passager": 0.4}


def combiner(courbes: list[tuple[dict, dict, float]]) -> tuple[dict, dict]:
    """Moyenne pondérée des écarts de plusieurs places : [(écarts, fiable, poids)] → (écarts, fiable).

    Une bande ne compte que là où la mesure est fiable (assez au-dessus du bruit de fond) ; elle est fiable dans le
    résultat si au moins une place l'est. L'égaliseur de l'autoradio est le même pour tout le monde : on vise le
    meilleur compromis, pas la perfection à une seule place.
    """
    freqs = list(courbes[0][0])
    ec, fi = {}, {}
    for f in freqs:
        pts = [(e[f], w) for e, ok, w in courbes if ok.get(f)]
        fi[f] = bool(pts)
        ec[f] = sum(v * w for v, w in pts) / sum(w for _, w in pts) if pts else 0.0
    return ec, fi


def mesurer_vibration(a: Autoradio, micro) -> float | None:
    a.onglet_son("Options")
    if not a.toucher_texte("Lancer la montée des basses"):
        raise SystemExit("Bouton « Lancer la montée des basses » introuvable (Lanceur 0.30 ou plus récent ?).")
    x = m.enregistrer(33, micro)
    m.sauver_wav(x, "auto-vibration")
    return m.frequence_vibration(x)


def svg_avant_apres(avant: dict, apres: dict | None) -> str:
    """Graphique : courbe avant (gris) et après (bleu) l'égaliseur."""
    fiables = {f: True for f in avant}
    s = m.svg_courbe(avant, fiables).replace("#1f6feb", "#9aa4b2")
    if apres:
        import math
        lw, lh, g, d, h, b = 900, 380, 60, 20, 20, 50
        fmin, fmax = math.log10(20), math.log10(20000)
        X = lambda f: g + (math.log10(f) - fmin) / (fmax - fmin) * (lw - g - d)
        Y = lambda db: h + (12 - max(-12, min(12, db))) / 24 * (lh - h - b)
        pts = " ".join(f"{X(f):.1f},{Y(v):.1f}" for f, v in apres.items())
        s = s.replace("</svg>", f'<polyline points="{pts}" fill="none" stroke="#1f6feb" stroke-width="3"/>'
                                 f'<text x="{lw - 230}" y="{h + 14}">gris : avant · bleu : après</text></svg>')
    return s


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--micro", type=int, default=None, help="numéro du micro (python mesure_son.py micros)")
    p.add_argument("--appareil", default=None, help="adresse ADB de l'autoradio (sinon recherche sur le Wi-Fi)")
    p.add_argument("--sans-eq", action="store_true", help="mesurer seulement, sans toucher à l'égaliseur")
    p.add_argument("--sans-vibration", action="store_true")
    p.add_argument("--oui", action="store_true", help="ne pas attendre Entrée entre les étapes (essais)")
    p.add_argument("--monter", type=int, default=0,
                   help="monte le volume de N crans (touches de volume) pour la recherche de vibration, puis le remet")
    p.add_argument("--deux-places", action="store_true",
                   help="mesure aussi à la place passager avant : égaliseur réglé pour les deux (60 %% conducteur, 40 %% passager)")
    p.add_argument("--champ-seul", default=None, metavar="DROITE,AVANT",
                   help="position « Seul » de la balance (croix Champ) à envoyer au Lanceur, ex. 1,3")
    a_ = p.parse_args(argv)
    global SANS_QUESTION
    SANS_QUESTION = a_.oui
    t0 = time.time()

    dev = a_.appareil or trouver_autoradio()
    if not dev:
        sys.exit("Autoradio introuvable : même partage de connexion ? « ADB par le Wi-Fi » activé dans le Lanceur ?")
    subprocess.run([ADB, "connect", dev], capture_output=True)
    a = Autoradio(ADB, dev)
    print(f"Autoradio : {dev}")

    demander("PC à ta place, micro à hauteur des oreilles, portes fermées, moteur coupé, VOLUME HABITUEL. Silence.")
    print("1/5 Bruit de fond (4 s)…")
    fondx = m.enregistrer(4, a_.micro)
    fond = m.niveaux_tiers(fondx) if len(fondx) > 8192 else {f: -200 for f in m.TIERS}

    print("2/5 Courbe de réponse (bruit rose, 14 s)…")
    avant, fiable, _ = mesurer_courbe(a, a_.micro, fond)
    avant_passager = None
    if a_.deux_places:
        print("   (place conducteur mesurée)")
        demander("Pose le PC sur le SIÈGE PASSAGER AVANT, micro à hauteur des oreilles. Silence.")
        print("2 bis/5 Courbe à la place passager (14 s)…")
        avant_passager, fiable_p, _ = mesurer_courbe(a, a_.micro, fond)
        cible, fiable_c = combiner([(avant, fiable, POIDS_PLACES["conducteur"]),
                                    (avant_passager, fiable_p, POIDS_PLACES["passager"])])
        eq = m.reglages_fyt({f: e for f, e in cible.items() if fiable_c[f]})
    else:
        eq = m.reglages_fyt({f: e for f, e in avant.items() if fiable[f]})
    boom = m.bosse_basses({f: e for f, e in avant.items() if fiable[f]})

    vib = None
    if not a_.sans_vibration:
        if a_.monter:
            print(f"   Volume +{a_.monter} crans (touches de volume de l'autoradio)…")
            for _ in range(a_.monter):
                a.sh("input", "keyevent", "24")
                time.sleep(0.3)
        else:
            demander("Monte le volume au niveau où les portes vibrent d'habitude.")
        print("3/5 Montée lente des basses (33 s)…")
        try:
            vib = mesurer_vibration(a, a_.micro)
        finally:
            if a_.monter:
                for _ in range(a_.monter):
                    a.sh("input", "keyevent", "25")
                    time.sleep(0.3)
                print("   Volume remis comme avant.")
        if not a_.monter:
            demander("Remets ton VOLUME HABITUEL.")
    resonance = int(round(vib)) if vib else (int(boom) if boom else 0)

    avant_eq = None
    if not a_.sans_eq:
        print("4/5 Égaliseur 15 bandes de l'autoradio…")
        fyt_eq.ouvrir_eq(a)
        avant_eq = fyt_eq.lire_points(a.capture())
        lu = fyt_eq.regler(a, eq)
        print("   réglé : " + ", ".join(f"{b:g} Hz {v:+.0f}" for b, v in lu.items()))
    envoi = {"resonanceHz": resonance, "bassAuto": True}
    if a_.champ_seul:
        envoi["champSeul"] = [int(v) for v in a_.champ_seul.split(",")]
    payload = json.dumps(envoi)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as fh:
        fh.write(payload)
    subprocess.run([ADB, "-s", dev, "push", fh.name, f"{FICHIERS_LANCEUR}/reglage-son.json"], capture_output=True)
    os.unlink(fh.name)

    apres = apres_passager = None
    if not a_.sans_eq:
        if a_.deux_places:
            print("5/5 Vérification à la place passager (bruit rose, 14 s)…")
            apres_passager, _, _ = mesurer_courbe(a, a_.micro, fond)
            demander("Remets le PC à TA PLACE (conducteur), micro à hauteur des oreilles.")
            print("5 bis/5 Vérification à la place conducteur (14 s)…")
        else:
            print("5/5 Vérification (bruit rose, 14 s)…")
        apres, _, _ = mesurer_courbe(a, a_.micro, fond)

    m.MESURES.mkdir(exist_ok=True)
    stamp = f"{dt.datetime.now():%Y%m%d-%H%M%S}"
    (m.MESURES / f"{stamp}-auto.svg").write_text(svg_avant_apres(avant, apres), encoding="utf-8")
    rapport = {
        "date": stamp, "autoradio": dev, "duree_s": round(time.time() - t0),
        "vibration_hz": vib, "bosse_basses_hz": boom, "resonance_envoyee_hz": resonance,
        "egaliseur_avant": avant_eq, "egaliseur_propose": eq,
        "ecart_avant": {str(k): round(v, 1) for k, v in avant.items()},
        "ecart_apres": {str(k): round(v, 1) for k, v in apres.items()} if apres else None,
        "deux_places": a_.deux_places,
        "ecart_avant_passager": {str(k): round(v, 1) for k, v in avant_passager.items()} if avant_passager else None,
        "ecart_apres_passager": {str(k): round(v, 1) for k, v in apres_passager.items()} if apres_passager else None,
    }
    if avant_passager:
        (m.MESURES / f"{stamp}-auto-passager.svg").write_text(svg_avant_apres(avant_passager, apres_passager), encoding="utf-8")
    (m.MESURES / f"{stamp}-auto.json").write_text(json.dumps(rapport, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n===== Résultat =====")
    print(f"Vibration des portes : {f'{vib:.0f} Hz' if vib else 'aucune détectée'}"
          + (f" ; bosse dans les basses vers {boom:g} Hz" if boom else ""))
    print(f"Envoyé au Lanceur : basses automatiques actives, fréquence baissée à volume fort {resonance or 'aucune'} Hz")
    if avant_eq:
        print("Égaliseur d'avant (pour revenir) : " + ", ".join(f"{b:g}:{v:+.0f}" for b, v in avant_eq.items()))
    def ecart_moyen(c):
        return sum(abs(v) for f, v in c.items() if 40 <= f <= 10000) / len([f for f in c if 40 <= f <= 10000])
    if apres:
        place = " (conducteur)" if a_.deux_places else ""
        print(f"Écart moyen à la courbe visée{place} : {ecart_moyen(avant):.1f} dB avant → {ecart_moyen(apres):.1f} dB après")
    if avant_passager and apres_passager:
        print(f"Écart moyen à la courbe visée (passager) : {ecart_moyen(avant_passager):.1f} dB avant → "
              f"{ecart_moyen(apres_passager):.1f} dB après")
    print(f"Graphique et rapport : {m.MESURES / (stamp + '-auto.svg')}  ({rapport['duree_s']} s)")


if __name__ == "__main__":
    main()
