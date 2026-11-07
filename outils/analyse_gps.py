"""Analyse des journaux GPS du Lanceur 308 (fichiers gps/AAAA-MM-JJ.csv).

Pour chaque signalement « Waze/Plans figé » fait par le passager, regarde si le GPS de l'autoradio
était figé au même moment : c'est ce qui dit si le problème vient de l'autoradio ou de l'iPhone.
Usage : python3 analyse_gps.py <dossier des .csv>
"""
import csv
import sys
from datetime import datetime, timedelta
from pathlib import Path

rows = []
for f in sorted(Path(sys.argv[1]).glob("*.csv")):
    with open(f, encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter=";"):
            try:
                r["t"] = datetime.fromisoformat(r["heure"])
            except ValueError:
                continue
            rows.append(r)
if not rows:
    sys.exit("Aucune ligne dans les journaux.")
rows.sort(key=lambda r: r["t"])


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


fixes = [r for r in rows if r["evenement"] in ("POSITION", "REPRISE")]
gels = [r for r in rows if r["evenement"] == "GEL"]
marks = [r for r in rows if r["evenement"] == "SIGNALEMENT_CARPLAY"]
sats = [num(r["sat_utilises"]) for r in fixes if num(r["sat_utilises"]) is not None]
prec = [num(r["precision_m"]) for r in fixes if num(r["precision_m"]) is not None]

# Trous : plus de 5 s sans position alors qu'on roulait (> 10 km/h) juste avant.
gaps = []
for a, b in zip(fixes, fixes[1:]):
    dt = (b["t"] - a["t"]).total_seconds()
    if dt > 5 and (num(a["vitesse_kmh"]) or 0) > 10:
        gaps.append((a["t"], dt))

print(f"Période : {rows[0]['t']:%d/%m %H:%M} → {rows[-1]['t']:%d/%m %H:%M}")
print(f"Positions reçues : {len(fixes)}")
if sats:
    print(f"Satellites utilisés : moyenne {sum(sats) / len(sats):.1f}, minimum {min(sats):.0f}")
if prec:
    bad = sum(1 for p in prec if p > 20)
    print(f"Précision : médiane {sorted(prec)[len(prec) // 2]:.0f} m, {100 * bad / len(prec):.0f} % du temps au-delà de 20 m")
print(f"Gels du GPS de l'autoradio détectés : {len(gels)} · coupures > 5 s en roulant : {len(gaps)}")
for g in gels[:10]:
    print(f"   {g['t']:%H:%M:%S}  {g['detail']}")
print(f"Signalements « Waze/Plans figé » : {len(marks)}")

verdicts = []
for m in marks:
    window = [r for r in rows if m["t"] - timedelta(seconds=90) <= r["t"] <= m["t"]]
    frozen = any(r["evenement"] == "GEL" for r in window)
    recent = [r for r in fixes if r["t"] <= m["t"]]
    age = (m["t"] - recent[-1]["t"]).total_seconds() if recent else None
    frozen = frozen or (age is not None and age > 5)
    last = recent[-1] if recent else None
    detail = f"dernière position il y a {age:.0f} s, {last['sat_utilises'] or '?'} satellites" if last else "aucune position"
    verdicts.append(frozen)
    print(f"   {m['t']:%H:%M:%S} → GPS de l'autoradio {'FIGÉ AUSSI' if frozen else 'normal'} ({detail})")

print()
if not marks:
    print("Conclusion : aucun signalement. Pendant un trajet, appuie sur « Waze / Plans vient de se figer »")
    print("au moment du problème (en passager), puis relance cette analyse.")
elif all(verdicts):
    print("Conclusion : à chaque gel de CarPlay, le GPS de l'autoradio était figé lui aussi.")
    print("L'iPhone utilise la position envoyée par l'autoradio. Correction : désactiver l'envoi du GPS")
    print("du véhicule dans l'appli CarPlay (ZLink), ou améliorer la réception de l'antenne GPS de l'autoradio.")
elif not any(verdicts):
    print("Conclusion : le GPS de l'autoradio fonctionnait pendant les gels de CarPlay.")
    print("Le problème vient plutôt de l'iPhone (sa propre position) ou de la liaison CarPlay sans fil :")
    print("essaie CarPlay en filaire, et vérifie Réglages iPhone → Confidentialité → Service de localisation.")
else:
    print("Conclusion : résultats mixtes. Certains gels coïncident avec le GPS de l'autoradio, d'autres non :")
    print("refais un trajet avec plusieurs signalements pour trancher.")
