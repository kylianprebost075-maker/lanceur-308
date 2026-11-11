"""Mesure du son de la voiture avec le micro du PC (le Lanceur 308 joue les sons, le PC écoute et analyse).

Trois mesures, à faire PC posé sur le siège conducteur, micro à hauteur des oreilles, moteur coupé :
  courbe      bruit rose → niveau par tiers d'octave, comparé à une courbe « voiture », réglages d'égaliseur proposés
  clics       clics gauche puis droite (500 ms d'écart exact) → décalage gauche / droite restant après l'alignement
  saturation  montée en volume d'un son pur à 400 Hz → distorsion à chaque palier, palier à ne pas dépasser
Autres commandes : micros (liste des micros du PC).

Tout reste sur le PC (dossier « mesures » à côté de ce fichier). Rien n'est envoyé à l'autoradio.
"""
from __future__ import annotations

import argparse
import datetime as dt
import math
import sys
import wave
from pathlib import Path

import numpy as np

RATE = 48_000
ICI = Path(__file__).resolve().parent
MESURES = ICI / "mesures"

# Tiers d'octave (fréquences centrales normalisées) de 25 Hz à 16 kHz.
TIERS = [25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000,
         2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000]
# Égaliseur 10 bandes courant sur les autoradios (et repères pour un égaliseur plus fin).
EQ10 = [31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]


def cible_voiture(f: float) -> float:
    """Courbe visée dans une voiture (dB) : basses un peu au-dessus (bruit de route), aigus légèrement en pente."""
    points = [(20, 5.0), (50, 4.5), (100, 3.0), (200, 1.0), (300, 0.0), (2000, 0.0), (5000, -1.5), (10000, -3.0),
              (20000, -4.5)]
    lf = math.log10(f)
    for (f1, g1), (f2, g2) in zip(points, points[1:]):
        if f1 <= f <= f2:
            x = (lf - math.log10(f1)) / (math.log10(f2) - math.log10(f1))
            return g1 + (g2 - g1) * x
    return points[0][1] if f < points[0][0] else points[-1][1]


# --------------------------------------------------------------------------------------------- analyse (testée)

def niveaux_tiers(x: np.ndarray, rate: int = RATE) -> dict[float, float]:
    """Énergie (dB) dans chaque tiers d'octave : spectre moyenné (Welch, fenêtres de 8192 points)."""
    n = 8192
    if len(x) < n:
        raise ValueError("enregistrement trop court")
    win = np.hanning(n)
    pas = n // 2
    acc = np.zeros(n // 2 + 1)
    k = 0
    for d in range(0, len(x) - n + 1, pas):
        seg = x[d:d + n] * win
        acc += np.abs(np.fft.rfft(seg)) ** 2
        k += 1
    psd = acc / max(k, 1)
    freqs = np.fft.rfftfreq(n, 1 / rate)
    out = {}
    for fc in TIERS:
        lo, hi = fc / 2 ** (1 / 6), fc * 2 ** (1 / 6)
        m = (freqs >= lo) & (freqs < hi)
        e = psd[m].sum() if m.any() else 0.0
        out[fc] = 10 * math.log10(e + 1e-20)
    return out


def ecarts(niveaux: dict[float, float]) -> dict[float, float]:
    """Écart à la cible (dB), après avoir calé la moyenne 250 Hz - 2 kHz sur 0."""
    ref = np.mean([v for f, v in niveaux.items() if 250 <= f <= 2000])
    return {f: (v - ref) - cible_voiture(f) for f, v in niveaux.items()}


def reglages_eq(ec: dict[float, float], bandes: list[float] = EQ10) -> dict[float, float]:
    """Réglage proposé par bande : l'inverse de l'écart moyen autour de la bande (±1/2 octave).
    On coupe volontiers (jusqu'à -8 dB) mais on monte peu (+4 dB max) : monter fait saturer l'ampli plus tôt."""
    out = {}
    for b in bandes:
        proches = [e for f, e in ec.items() if b / 2 ** 0.5 <= f <= b * 2 ** 0.5]
        if not proches:
            continue
        out[b] = float(np.clip(-np.mean(proches), -8.0, 4.0))
    return out


def detecter_clics(x: np.ndarray, rate: int = RATE) -> list[float]:
    """Instants (s) du début de chaque clic : passe-haut, enveloppe, seuil à 20 % du pic de chaque clic."""
    # Passe-haut simple (différence) : enlève le grave de la pièce et du moteur.
    y = np.abs(np.diff(x, prepend=x[0]))
    w = max(1, int(0.0005 * rate))
    env = np.convolve(y, np.ones(w) / w, mode="same")
    # Rien qui dépasse nettement le bruit de fond : pas de clic (et pas de faux clics dans le bruit).
    if env.max() < 8 * np.median(env) + 1e-9:
        return []
    seuil = 0.25 * env.max()
    instants = []
    i = 0
    mini = int(0.2 * rate)
    while i < len(env):
        if env[i] > seuil:
            fin = min(len(env), i + int(0.01 * rate))
            pic = i + int(np.argmax(env[i:fin]))
            debut = max(0, pic - int(0.005 * rate))
            j = debut + int(np.argmax(env[debut:pic + 1] >= 0.2 * env[pic]))
            instants.append(j / rate)
            i = pic + mini
        else:
            i += 1
    return instants


def ecart_gauche_droite(instants: list[float], prevu: float = 0.5) -> tuple[float, float, int] | None:
    """Écart moyen (ms) entre l'arrivée réelle du clic droit et les 500 ms prévues, son écart-type, nombre de paires.
    Positif : la droite arrive en retard sur la gauche."""
    ecarts_ms = []
    for a, b in zip(instants, instants[1:]):
        d = b - a
        # Paire gauche → droite : écart proche de 0,5 s (la droite → gauche suivante est à ≈ 1 s).
        if abs(d - prevu) < 0.1:
            ecarts_ms.append((d - prevu) * 1000)
    if not ecarts_ms:
        return None
    return float(np.median(ecarts_ms)), float(np.std(ecarts_ms)), len(ecarts_ms)


def thd(x: np.ndarray, f0: float = 400.0, rate: int = RATE) -> float:
    """Distorsion harmonique (%) : énergie des harmoniques 2 à 10 / énergie du fondamental."""
    n = len(x)
    spec = np.abs(np.fft.rfft(x * np.blackman(n))) ** 2
    freqs = np.fft.rfftfreq(n, 1 / rate)
    df = freqs[1]

    def energie(f):
        m = np.abs(freqs - f) <= 3 * df
        return spec[m].sum()

    fond = energie(f0)
    harm = sum(energie(f0 * k) for k in range(2, 11) if f0 * k < rate / 2)
    return 100 * math.sqrt(harm / fond) if fond > 0 else float("nan")


def segments_son(x: np.ndarray, rate: int = RATE) -> list[tuple[int, int]]:
    """Paliers de son pur (début, fin en échantillons) séparés par des silences : enveloppe RMS sur 50 ms."""
    w = int(0.05 * rate)
    k = len(x) // w
    if k == 0:
        return []
    rms = np.sqrt((x[:k * w].reshape(k, w) ** 2).mean(axis=1))
    bruit = np.percentile(rms, 10)
    seuil = max(bruit * 4, rms.max() * 0.02)
    actif = rms > seuil
    segs = []
    i = 0
    while i < k:
        if actif[i]:
            j = i
            while j < k and actif[j]:
                j += 1
            if (j - i) * 0.05 >= 1.0:  # au moins 1 s : un palier (2 s prévues)
                segs.append((i * w, j * w))
            i = j
        else:
            i += 1
    return segs


def analyse_saturation(x: np.ndarray, rate: int = RATE, limite: float = 3.0) -> list[dict]:
    """Distorsion de chaque palier (sur la seconde du milieu) ; « micro saturé » si le PC lui-même écrête."""
    res = []
    for k, (a, b) in enumerate(segments_son(x, rate)):
        mil = (a + b) // 2
        seg = x[mil - rate // 2: mil + rate // 2]
        res.append({
            "palier": k + 1,
            "thd": thd(seg, 400.0, rate),
            "niveau_db": 20 * math.log10(np.sqrt((seg ** 2).mean()) + 1e-12),
            "micro_sature": bool(np.abs(seg).max() >= 0.98),
        })
    for r in res:
        r["trop"] = r["thd"] > limite
    return res


# Égaliseur 15 bandes des autoradios FYT (appli com.syu.eq, onglet DSP), relevé sur l'autoradio le 11/11/2026.
FYT15 = [25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000]


def reglages_fyt(ec: dict[float, float]) -> dict[float, float]:
    """Réglage proposé pour chacune des 15 bandes FYT (pas de 1 dB), d'après l'écart à la cible.
    Fenêtre d'un tiers d'octave autour de chaque bande (elles sont espacées de 2/3 d'octave) ; même prudence que
    reglages_eq : on coupe jusqu'à -8 dB, on monte de +4 dB au plus. Sous 40 Hz, jamais de montée (portes sans caisson)."""
    out = {}
    for b in FYT15:
        proches = [e for f, e in ec.items() if b / 2 ** (1 / 3) <= f <= b * 2 ** (1 / 3)]
        if not proches:
            out[b] = 0.0
            continue
        g = float(np.clip(-np.mean(proches), -8.0, 4.0))
        if b < 40:
            g = min(g, 0.0)
        out[b] = float(round(g))
    return out


def frequence_vibration(x: np.ndarray, rate: int = RATE, fmin: float = 25.0, fmax: float = 170.0) -> float | None:
    """Fréquence (Hz) où quelque chose vibre pendant une montée lente de son pur dans les basses (30 → 150 Hz).
    Par tranches de 0,25 s : la fréquence jouée est le pic entre fmin et fmax ; une vibration (porte, garniture)
    ajoute un grésillement large au-dessus de 300 Hz. On cherche la tranche où ce grésillement, rapporté au son joué,
    dépasse le plus son niveau habituel (au moins 8 dB au-dessus de la médiane). None si rien ne vibre."""
    n = int(0.25 * rate)
    if len(x) < 8 * n:
        return None
    win = np.hanning(n)
    freqs = np.fft.rfftfreq(n, 1 / rate)
    bas = (freqs >= fmin) & (freqs <= fmax)
    haut = (freqs >= 300) & (freqs <= 4000)
    f0s, ratios, niveaux = [], [], []
    for d in range(0, len(x) - n + 1, n):
        spec = np.abs(np.fft.rfft(x[d:d + n] * win)) ** 2
        i = int(np.argmax(np.where(bas, spec, 0)))
        e0 = spec[max(0, i - 2):i + 3].sum()
        f0 = freqs[i]
        # Retire les harmoniques du son joué (une distorsion légère n'est pas une vibration de porte).
        masque = haut.copy()
        for k in range(2, 40):
            masque &= np.abs(freqs - k * f0) > 3 * freqs[1]
        eh = spec[masque].sum()
        f0s.append(f0)
        niveaux.append(e0)
        ratios.append(10 * math.log10((eh + 1e-20) / (e0 + 1e-20)))
    niveaux = np.array(niveaux)
    ratios = np.array(ratios)
    actif = niveaux > 0.05 * niveaux.max()  # tranches où le son de test joue vraiment
    if actif.sum() < 6:
        return None
    r = np.where(actif, ratios, -1e9)
    i = int(np.argmax(r))
    if r[i] - np.median(ratios[actif]) < 8.0:
        return None
    return float(f0s[i])


def bosse_basses(ec: dict[float, float], fmin: float = 40, fmax: float = 200, seuil: float = 3.0) -> float | None:
    """Tiers d'octave le plus « boumeux » entre fmin et fmax (écart à la cible ≥ seuil dB), ou None."""
    cand = {f: e for f, e in ec.items() if fmin <= f <= fmax and e >= seuil}
    return max(cand, key=cand.get) if cand else None


# --------------------------------------------------------------------------------------------- enregistrement

def enregistrer(secondes: float, micro: int | None) -> np.ndarray:
    """Enregistre en mono 48 kHz. Ctrl+C arrête plus tôt (ce qui est enregistré est gardé)."""
    import sounddevice as sd

    morceaux = []

    def cb(data, frames, t, status):
        morceaux.append(data[:, 0].copy())

    extra = None
    try:
        extra = sd.WasapiSettings(exclusive=True)  # contourne les « améliorations » du son de Windows si possible
        stream = sd.InputStream(samplerate=RATE, channels=1, dtype="float32", device=micro, callback=cb,
                                extra_settings=extra)
    except Exception:
        stream = sd.InputStream(samplerate=RATE, channels=1, dtype="float32", device=micro, callback=cb)
        extra = None
    print(f"   Enregistrement ({'exclusif' if extra else 'partagé'})… Ctrl+C pour arrêter plus tôt.")
    try:
        with stream:
            sd.sleep(int(secondes * 1000))
    except KeyboardInterrupt:
        print("   Arrêté.")
    return np.concatenate(morceaux) if morceaux else np.zeros(0, dtype="float32")


def sauver_wav(x: np.ndarray, nom: str) -> Path:
    MESURES.mkdir(exist_ok=True)
    p = MESURES / f"{dt.datetime.now():%Y%m%d-%H%M%S}-{nom}.wav"
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    return p


def svg_courbe(ec: dict[float, float], fiable: dict[float, bool]) -> str:
    """Petit graphique SVG de l'écart à la cible (lisible dans le navigateur)."""
    lw, lh, g, d, h, b = 900, 380, 60, 20, 20, 50
    fmin, fmax = math.log10(20), math.log10(20000)

    def X(f):
        return g + (math.log10(f) - fmin) / (fmax - fmin) * (lw - g - d)

    def Y(db):
        return h + (12 - max(-12, min(12, db))) / 24 * (lh - h - b)

    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{lw}" height="{lh}" font-family="sans-serif" font-size="12">',
         f'<rect width="{lw}" height="{lh}" fill="#fff"/>']
    for db in range(-12, 13, 3):
        s.append(f'<line x1="{g}" x2="{lw - d}" y1="{Y(db):.1f}" y2="{Y(db):.1f}" stroke="{"#888" if db == 0 else "#ddd"}"/>')
        s.append(f'<text x="{g - 8}" y="{Y(db) + 4:.1f}" text-anchor="end">{db:+d} dB</text>')
    for f in [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000]:
        s.append(f'<line x1="{X(f):.1f}" x2="{X(f):.1f}" y1="{h}" y2="{lh - b}" stroke="#eee"/>')
        s.append(f'<text x="{X(f):.1f}" y="{lh - b + 16}" text-anchor="middle">{f if f < 1000 else str(f // 1000) + "k"}</text>')
    pts = " ".join(f"{X(f):.1f},{Y(v):.1f}" for f, v in ec.items())
    s.append(f'<polyline points="{pts}" fill="none" stroke="#1f6feb" stroke-width="2.5"/>')
    for f, v in ec.items():
        s.append(f'<circle cx="{X(f):.1f}" cy="{Y(v):.1f}" r="3.5" fill="{"#1f6feb" if fiable[f] else "#bbb"}"/>')
    s.append(f'<text x="{g}" y="{lh - 12}">Écart à la courbe visée (0 = parfait ; au-dessus = trop fort). '
             'Points gris : bruit de fond trop proche, pas fiable.</text>')
    s.append("</svg>")
    return "\n".join(s)


# --------------------------------------------------------------------------------------------- commandes

def attendre(msg: str):
    input(f"\n>> {msg}\n   Puis appuie sur Entrée ici. ")


def cmd_micros(_):
    import sounddevice as sd
    for i, d in enumerate(sd.query_devices()):
        if d["max_input_channels"] > 0:
            print(f"{i:3d}  {d['name']}")
    print("\nPour en choisir un : --micro NUMÉRO")


def cmd_courbe(a):
    print("Mesure de la courbe de réponse.")
    attendre("Silence complet dans la voiture (aucun son), on mesure le bruit de fond 4 s.")
    fond = enregistrer(4, a.micro)
    attendre("Sur l'autoradio : Son → Mesure au PC → « Bruit rose les deux » (volume habituel d'écoute).")
    x = enregistrer(a.secondes, a.micro)
    sauver_wav(x, "courbe")
    if len(x) < RATE * 3:
        sys.exit("Enregistrement trop court.")
    x = x[RATE:]  # la première seconde : le temps de lancer le son
    niv = niveaux_tiers(x)
    nf = niveaux_tiers(fond) if len(fond) > 8192 else {f: -200 for f in TIERS}
    fiable = {f: niv[f] - nf[f] >= 10 for f in TIERS}
    ec = ecarts(niv)
    eq = reglages_eq({f: e for f, e in ec.items() if fiable[f]})
    print("\nÉcart à la courbe visée par tiers d'octave (+ = trop fort) :")
    for f in TIERS:
        barre = "█" * int(min(12, abs(ec[f])))
        print(f"  {f:>7g} Hz  {ec[f]:+5.1f} dB  {'+' if ec[f] > 0 else '-'}{barre}{'' if fiable[f] else '   (bruit de fond, pas fiable)'}")
    print("\nRéglages d'égaliseur proposés (égaliseur 10 bandes de l'autoradio) :")
    for b, g in eq.items():
        print(f"  {b:>7g} Hz : {g:+.0f} dB")
    print("\nÀ savoir : un micro de PC capte mal les basses sous 80 Hz. N'y monte pas l'égaliseur de plus de +3 dB ;"
          "\nfie-toi à ton oreille pour le grave. Refais la mesure après réglage pour vérifier.")
    MESURES.mkdir(exist_ok=True)
    p = MESURES / f"{dt.datetime.now():%Y%m%d-%H%M%S}-courbe.svg"
    p.write_text(svg_courbe(ec, fiable), encoding="utf-8")
    print(f"\nGraphique : {p}")


def cmd_clics(a):
    print("Vérification de l'alignement gauche / droite.")
    attendre("Fader de l'autoradio tout à l'AVANT (ou tout à l'arrière), puis Son → Mesure au PC → « Clics gauche / droite ».")
    x = enregistrer(a.secondes, a.micro)
    sauver_wav(x, "clics")
    r = ecart_gauche_droite(detecter_clics(x))
    if r is None:
        sys.exit("Pas de clics reconnus : monte un peu le volume et rapproche le PC de ta place.")
    ms, sd_, n = r
    print(f"\n{n} paires de clics. La droite arrive {abs(ms):.2f} ms {'APRÈS' if ms > 0 else 'AVANT'} la gauche "
          f"(variation ± {sd_:.2f} ms).")
    if sd_ > 0.5:
        print("Mesure peu stable (écho, bruit) : refais-la, portes fermées.")
    if abs(ms) < 0.1:
        print("Parfait : gauche et droite arrivent ensemble.")
    elif ms > 0:
        print(f"Correction : ajoute {ms:.2f} ms au retard du côté GAUCHE (ou retire-les du côté droit).")
    else:
        print(f"Correction : ajoute {-ms:.2f} ms au retard du côté DROIT (ou retire-les du côté gauche).")


def cmd_saturation(a):
    print("Recherche du volume maximum sans saturation.")
    print("Pose le PC à ta place ; tu peux sortir de la voiture pendant la montée (le son devient fort).")
    attendre("Sur l'autoradio : Son → Mesure au PC → choisis le cran de départ, puis « Lancer la montée en volume ».")
    x = enregistrer(a.secondes, a.micro)
    sauver_wav(x, "saturation")
    res = analyse_saturation(x)
    if not res:
        sys.exit("Aucun palier reconnu.")
    print(f"\n{'Palier':>6}  {'Volume':>6}  {'Distorsion':>10}  Niveau")
    premier = None
    for r in res:
        vol = (a.depart + r["palier"] - 1) if a.depart is not None else "?"
        note = "  ← micro du PC saturé (pas l'ampli) : éloigne-le" if r["micro_sature"] else ("  ← SATURE" if r["trop"] else "")
        print(f"{r['palier']:>6}  {vol:>6}  {r['thd']:>9.1f} %  {r['niveau_db']:6.1f} dB{note}")
        if premier is None and r["trop"] and not r["micro_sature"]:
            premier = r
    if premier is None:
        print("\nAucune saturation de l'ampli jusqu'au dernier palier mesuré.")
    else:
        ok = premier["palier"] - 1
        txt = f"cran {a.depart + ok - 1}" if a.depart is not None and ok >= 1 else f"palier {ok}"
        print(f"\nL'ampli sature au palier {premier['palier']}. Volume max conseillé : {txt} "
              "(avec une musique très compressée, reste un cran en dessous).")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("micros").set_defaults(f=cmd_micros)
    for nom, f, sec in [("courbe", cmd_courbe, 15), ("clics", cmd_clics, 12), ("saturation", cmd_saturation, 150)]:
        s = sub.add_parser(nom)
        s.add_argument("--micro", type=int, default=None, help="numéro du micro (voir « micros »)")
        s.add_argument("--secondes", type=float, default=sec)
        if nom == "saturation":
            s.add_argument("--depart", type=int, default=None, help="cran de départ choisi sur l'autoradio")
        s.set_defaults(f=f)
    a = p.parse_args(argv)
    a.f(a)


if __name__ == "__main__":
    main()
