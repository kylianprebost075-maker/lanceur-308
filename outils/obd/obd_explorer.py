"""Explorateur OBD en LECTURE SEULE pour la Peugeot 308 T7 1.6 HDi (boîtier ELM327 type Vgate vLinker MC+).

But : découvrir, dans la voiture, tout ce que les calculateurs acceptent de donner (OBD normalisé, puis
questions propres à PSA), pour programmer ensuite la version suivante du Lanceur 308.

Sécurité : chaque commande passe par `commande_autorisee()`. Seules les lectures sont permises (modes OBD
01/02/03/07/09/0A, services de diagnostic 1A/17/18/19/21/22, maintien de session 3E, ouverture de session
normale ou étendue). Effacement des codes, écriture, tests d'actionneurs, accès sécurisé, reprogrammation et
remise à zéro du calculateur sont refusés, sans exception.

Connexion : Bluetooth classique (RFCOMM) de Windows, sans module externe. Appairer d'abord
« vLinker MC-Android » (code 1234) dans les paramètres Bluetooth de Windows.

Commandes (lancer avec `python -I obd_explorer.py <commande>`) :
  connexion                 test du boîtier (version, tension, protocole)
  standard                  OBD normalisé : PID gérés et valeurs, VIN, codes défaut (mémorisés, en attente, permanents)
  psa                       cherche les calculateurs PSA qui répondent, puis lit tous leurs identifiants 21xx
  instantane --nom NOM      relit les identifiants trouvés par `psa` et les enregistre sous NOM
  comparer NOM1 NOM2        octets qui ont changé entre deux instantanés (ex. phares éteints / allumés)
  chercher-km KM            cherche le kilométrage du compteur dans les instantanés
  direct --minutes N        relève en continu les valeurs OBD normalisées (moteur tournant)
  balayer --calculateur X --service 21|22 --nom NOM [--plages 0000-00FF,...] [--relire NOM0]
                            lit des plages d'identifiants (21xx ou 22xxxx) d'un calculateur et les enregistre comme
                            un instantané (comparable avec `comparer`) ; --relire ne relit que ceux d'un instantané
Option commune : --simulation (fausse voiture, pour essayer sans le boîtier), --mac AA:BB:..., --dossier.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import socket
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

DOSSIER_DEFAUT = Path(__file__).resolve().parent / "journaux"

# Calculateurs PSA candidats sur la prise diagnostic (CAN 500 kbit/s, 11 bits) : (nom, requête, réponse).
# Paires couramment citées pour les PSA de cette époque ; À CONFIRMER dans la voiture (la découverte le dira).
CALCULATEURS_PSA = [
    ("Moteur (CMM, EDC16C34)", 0x6A8, 0x688),
    ("BSI (boîtier de servitude)", 0x752, 0x652),
    ("Combiné (compteur)", 0x75F, 0x65F),
    ("ABS / ESP", 0x6AD, 0x68D),
    ("Airbag", 0x6B5, 0x695),
    ("Boîte auto (BVA)", 0x6A9, 0x689),
    ("Climatisation", 0x76D, 0x66D),
    ("Autoradio / écran (EMF)", 0x764, 0x664),
    ("Direction assistée", 0x6B4, 0x694),
    ("Aide au stationnement", 0x76C, 0x66C),
]

# ------------------------------------------------------------------ sécurité : liste blanche

AT_INTERDITES = ("ATPP", "ATBRD", "ATBRT", "ATWS", "ATLP", "ATMA", "ATMR", "ATMT", "ATBD", "ATCSM")
SERVICES_LECTURE = {0x01, 0x02, 0x03, 0x07, 0x09, 0x0A, 0x1A, 0x17, 0x18, 0x21, 0x22, 0x3E}
SESSIONS_PERMISES = {0x01, 0x03, 0x81, 0xC0}  # normale / étendue ; jamais programmation (0x02, 0x85)
SOUS_FONCTIONS_19 = {0x01, 0x02, 0x0A}  # lecture des codes (UDS)


class CommandeRefusee(Exception):
    """Commande hors de la liste blanche de lecture : elle n'est jamais envoyée au boîtier."""


def commande_autorisee(commande: str) -> bool:
    """Vrai seulement pour une commande de configuration du boîtier ou une LECTURE de diagnostic."""
    c = commande.replace(" ", "").upper()
    if not c:
        return False
    if c.startswith("AT"):
        return not c.startswith(AT_INTERDITES)
    if c in ("@1", "STI", "VTI"):
        return True
    if not re.fullmatch(r"(?:[0-9A-F]{2})+", c):
        return False
    octets = bytes.fromhex(c)
    service = octets[0]
    if service == 0x10:
        return len(octets) == 2 and octets[1] in SESSIONS_PERMISES
    if service == 0x19:
        return len(octets) >= 2 and octets[1] in SOUS_FONCTIONS_19
    return service in SERVICES_LECTURE


# ------------------------------------------------------------------ transports

class TransportRfcomm:
    """Liaison Bluetooth classique (port série SPP) avec le boîtier, par le module socket de Python."""

    def __init__(self, mac: str, canal: int = 1):
        self.sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        self.sock.settimeout(10)
        self.sock.connect((mac, canal))

    def echanger(self, texte: str, delai: float) -> str:
        """Envoie une commande et lit la réponse jusqu'à l'invite « > » du boîtier."""
        self.sock.sendall((texte + "\r").encode("ascii"))
        fin = time.monotonic() + delai
        tampon = b""
        while time.monotonic() < fin:
            self.sock.settimeout(max(0.05, fin - time.monotonic()))
            try:
                morceau = self.sock.recv(1024)
            except socket.timeout:
                break
            if not morceau:
                raise ConnectionError("liaison Bluetooth fermée")
            tampon += morceau
            if b">" in tampon:
                break
        return tampon.decode("ascii", "replace").split(">")[0]

    def fermer(self):
        """Ferme la liaison Bluetooth."""
        try:
            self.sock.close()
        except OSError:
            pass


class TransportSimulation:
    """Fausse 308 pour essayer le programme sans voiture (valeurs plausibles, VIN factice)."""

    VIN = "VF3SIMULATIO00001"

    def __init__(self):
        self.entete = 0x7DF
        self.phares = False  # basculé par les tests pour vérifier `comparer`
        self.moteur = False  # moteur tournant : change la valeur 22 0100 du calculateur moteur

    def _reponse(self, entete_rep: int, charge: bytes) -> list[str]:
        """Découpe une réponse en trames ISO-TP comme le boîtier les affiche (en-têtes visibles)."""
        h = f"{entete_rep:03X}"
        if len(charge) <= 7:
            return [h + " " + " ".join(f"{b:02X}" for b in bytes([len(charge)]) + charge)]
        lignes = [h + " " + " ".join(f"{b:02X}" for b in bytes([0x10 | (len(charge) >> 8), len(charge) & 0xFF]) + charge[:6])]
        reste, n = charge[6:], 1
        while reste:
            lignes.append(h + " " + " ".join(f"{b:02X}" for b in bytes([0x20 | (n & 0xF)]) + reste[:7]))
            reste, n = reste[7:], n + 1
        return lignes

    def echanger(self, texte: str, delai: float) -> str:
        """Répond comme un vLinker branché sur une 308 HDi."""
        c = texte.replace(" ", "").upper()
        if c.startswith("ATSH"):
            self.entete = int(c[4:], 16)
            return "OK"
        if c == "ATI":
            return "ELM327 v2.3"
        if c == "ATRV":
            return "12.6V"
        if c == "ATDP":
            return "ISO 15765-4 (CAN 11/500)"
        if c.startswith("AT") or c in ("@1", "STI", "VTI"):
            return "OK"
        req = bytes.fromhex(c)
        if self.entete == 0x7DF:
            return "\r".join(self._obd(req)) or "NO DATA"
        pairs = {0x6A8: 0x688, 0x752: 0x652}
        if self.entete not in pairs:
            return "NO DATA"
        rep = pairs[self.entete]
        s = req[0]
        if s == 0x3E:
            return "\r".join(self._reponse(rep, bytes([0x7E, 0x00])))
        if s == 0x10:
            return "\r".join(self._reponse(rep, bytes([0x50, req[1]])))
        if s == 0x1A:
            return "\r".join(self._reponse(rep, bytes([0x5A, req[1]]) + b"SIMU-" + bytes([0x96, 0x55, 0x00, 0x01])))
        if s == 0x21:
            lid = req[1]
            if self.entete == 0x752 and lid == 0x10:  # kilométrage 123 456 km sur 3 octets
                return "\r".join(self._reponse(rep, bytes([0x61, lid, 0x01, 0xE2, 0x40, 0x00])))
            if self.entete == 0x752 and lid == 0x20:  # état des feux
                return "\r".join(self._reponse(rep, bytes([0x61, lid, 0x05 if self.phares else 0x00, 0x00])))
            if self.entete == 0x6A8 and lid in (0xA0, 0xA1):
                return "\r".join(self._reponse(rep, bytes([0x61, lid]) + bytes(range(1, 15))))
            return "\r".join(self._reponse(rep, bytes([0x7F, 0x21, 0x12])))
        if s == 0x22 and self.entete == 0x6A8 and len(req) == 3:
            ident = int.from_bytes(req[1:3], "big")
            if ident == 0x0100:  # fausse pression différentielle du FAP
                return "\r".join(self._reponse(rep, bytes([0x62, 0x01, 0x00, 0x00, 0x2A if self.moteur else 0x00])))
            if ident == 0x0101:  # fausse donnée fixe
                return "\r".join(self._reponse(rep, bytes([0x62, 0x01, 0x01, 0x12, 0x34])))
            return "\r".join(self._reponse(rep, bytes([0x7F, 0x22, 0x31])))
        return "\r".join(self._reponse(rep, bytes([0x7F, s, 0x11])))

    def _obd(self, req: bytes) -> list[str]:
        """Modes OBD normalisés du calculateur moteur simulé."""
        mode = req[0]
        geres = {0x00: [0x98, 0x3B, 0xA0, 0x13], 0x20: [0xA0, 0x11, 0xA0, 0x01], 0x40: [0x40, 0x00, 0x00, 0x00]}
        valeurs = {0x04: [0x40], 0x05: [0x7B], 0x0B: [0x96], 0x0C: [0x0D, 0xAC], 0x0D: [0x00], 0x10: [0x01, 0x90],
                   0x11: [0x20], 0x1C: [0x06], 0x1F: [0x00, 0x78], 0x21: [0x00, 0x00], 0x23: [0x0F, 0xA0],
                   0x2C: [0x33], 0x31: [0x12, 0x34], 0x33: [0x65], 0x42: [0x32, 0xC8]}
        if mode == 0x01 and len(req) == 2:
            pid = req[1]
            donnees = geres.get(pid) or valeurs.get(pid)
            return self._reponse(0x7E8, bytes([0x41, pid] + donnees)) if donnees else []
        if mode == 0x03:
            return self._reponse(0x7E8, bytes([0x43, 0x01, 0x04, 0x01]))  # P0401 : débit EGR insuffisant
        if mode in (0x07, 0x0A):
            return self._reponse(0x7E8, bytes([mode + 0x40, 0x00]))
        if mode == 0x09 and req[1] == 0x00:
            return self._reponse(0x7E8, bytes([0x49, 0x00, 0x54, 0x00, 0x00, 0x00]))
        if mode == 0x09 and req[1] == 0x02:
            return self._reponse(0x7E8, bytes([0x49, 0x02, 0x01]) + self.VIN.encode())
        return []

    def fermer(self):
        """Rien à fermer en simulation."""


def trouver_mac_vlinker() -> str | None:
    """Adresse Bluetooth du vLinker appairé dans Windows (lue dans la liste des périphériques), ou None."""
    try:
        sortie = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-PnpDevice -Class Bluetooth | Where-Object FriendlyName -match 'vlink|obd|elm|vgate' | "
             "ForEach-Object { $_.FriendlyName + '|' + $_.InstanceId }"],
            capture_output=True, text=True, timeout=30, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    for ligne in sortie.splitlines():
        # « BTHENUM\DEV_001122334455\… » ou « …&0&001122334455_C00000000 » (le GUID du service est ignoré)
        m = re.search(r"DEV_([0-9A-F]{12})|[&_]([0-9A-F]{12})_C", ligne.upper())
        if m:
            h = m.group(1) or m.group(2)
            return ":".join(h[i:i + 2] for i in range(0, 12, 2))
    return None


# ------------------------------------------------------------------ boîtier ELM327

def reassembler(lignes: list[str]) -> dict[int, bytes]:
    """Regroupe les trames ISO-TP affichées par le boîtier (en-têtes visibles) en un message par calculateur."""
    messages: dict[int, bytearray] = {}
    attendu: dict[int, int] = {}
    for ligne in lignes:
        jetons = ligne.split()
        if len(jetons) < 2 or not re.fullmatch(r"[0-9A-F]{3}", jetons[0]):
            continue
        try:
            entete = int(jetons[0], 16)
            donnees = bytes(int(j, 16) for j in jetons[1:])
        except ValueError:
            continue
        pci = donnees[0] >> 4
        if pci == 0:
            n = donnees[0] & 0x0F
            charge = donnees[1:1 + n]
            if entete in messages and len(charge) >= 3 and charge[0] == 0x7F and charge[2] == 0x78:
                continue  # « réponse en préparation » : on garde ce qui suit
            messages[entete] = bytearray(charge)
            attendu[entete] = n
        elif pci == 1:
            attendu[entete] = ((donnees[0] & 0x0F) << 8) | donnees[1]
            messages[entete] = bytearray(donnees[2:])
        elif pci == 2 and entete in messages:
            messages[entete] += donnees[1:]
    return {h: bytes(m[:attendu.get(h, len(m))]) for h, m in messages.items()}


@dataclass
class Journal:
    """Tout ce qui est envoyé et reçu, horodaté, pour pouvoir tout relire après la séance."""
    chemin: Path
    lignes: list[str] = field(default_factory=list)

    def ecrire(self, sens: str, texte: str):
        """Ajoute une ligne au journal (et l'écrit tout de suite, au cas où le PC s'éteint)."""
        t = dt.datetime.now().strftime("%H:%M:%S.%f")[:-3]
        ligne = f"{t} {sens} {texte.strip()}"
        self.lignes.append(ligne)
        with self.chemin.open("a", encoding="utf-8") as f:
            f.write(ligne + "\n")


class Elm:
    """Dialogue avec le boîtier ELM327, toujours à travers la liste blanche de lecture."""

    def __init__(self, transport, journal: Journal):
        self.t = transport
        self.journal = journal

    def brut(self, commande: str, delai: float = 2.0) -> list[str]:
        """Envoie une commande autorisée et renvoie les lignes de réponse utiles."""
        if not commande_autorisee(commande):
            self.journal.ecrire("REFUS", commande)
            raise CommandeRefusee(commande)
        self.journal.ecrire(">>", commande)
        texte = self.t.echanger(commande, delai)
        self.journal.ecrire("<<", texte.replace("\r", " | "))
        return [l.strip() for l in re.split(r"[\r\n]+", texte) if l.strip() and l.strip() not in ("SEARCHING...", "BUS INIT: ...OK")]

    def demander(self, hexa: str, delai: float = 2.0) -> dict[int, bytes]:
        """Envoie une requête de diagnostic et renvoie la réponse de chaque calculateur."""
        return reassembler(self.brut(hexa, delai))

    def initialiser(self) -> dict:
        """Remet le boîtier à zéro et le règle pour la 308 (CAN 11 bits 500 kbit/s, en-têtes visibles)."""
        self.brut("ATZ", 3.0)
        time.sleep(0.3)
        for c in ("ATE0", "ATL0", "ATS1", "ATH1", "ATAT1", "ATST64", "ATSP6", "ATCAF1"):
            self.brut(c)
        return {"version": " ".join(self.brut("ATI")), "tension": " ".join(self.brut("ATRV")),
                "protocole": " ".join(self.brut("ATDP"))}

    def viser(self, requete: int, reponse: int | None):
        """Adresse les requêtes à un calculateur précis (ou à tous, pour l'OBD normalisé)."""
        self.brut(f"ATSH{requete:03X}")
        if reponse is None:
            self.brut("ATCRA")  # filtre par défaut : 7E8 à 7EF
            self.brut("ATFCSM0")
        else:
            self.brut(f"ATCRA{reponse:03X}")
            self.brut(f"ATFCSH{requete:03X}")
            self.brut("ATFCSD300000")
            self.brut("ATFCSM1")


# ------------------------------------------------------------------ décodage OBD normalisé

PIDS = {
    0x04: ("Charge moteur calculée", "%", lambda d: d[0] * 100 / 255),
    0x05: ("Température d'eau", "°C", lambda d: d[0] - 40),
    0x0B: ("Pression d'admission (absolue)", "kPa", lambda d: d[0]),
    0x0C: ("Régime", "tr/min", lambda d: (d[0] * 256 + d[1]) / 4),
    0x0D: ("Vitesse", "km/h", lambda d: d[0]),
    0x0F: ("Température d'air admis", "°C", lambda d: d[0] - 40),
    0x10: ("Débit d'air", "g/s", lambda d: (d[0] * 256 + d[1]) / 100),
    0x11: ("Position papillon / pédale", "%", lambda d: d[0] * 100 / 255),
    0x1C: ("Norme OBD du véhicule", "", lambda d: d[0]),
    0x1F: ("Temps depuis le démarrage", "s", lambda d: d[0] * 256 + d[1]),
    0x21: ("Distance parcourue voyant moteur allumé", "km", lambda d: d[0] * 256 + d[1]),
    0x23: ("Pression du rail gazole", "kPa", lambda d: (d[0] * 256 + d[1]) * 10),
    0x2C: ("Vanne EGR : ouverture demandée", "%", lambda d: d[0] * 100 / 255),
    0x2D: ("Vanne EGR : écart", "%", lambda d: d[0] * 100 / 128 - 100),
    0x30: ("Démarrages à chaud depuis l'effacement des codes", "", lambda d: d[0]),
    0x31: ("Distance depuis l'effacement des codes", "km", lambda d: d[0] * 256 + d[1]),
    0x33: ("Pression atmosphérique", "kPa", lambda d: d[0]),
    0x42: ("Tension du calculateur", "V", lambda d: (d[0] * 256 + d[1]) / 1000),
    0x46: ("Température extérieure", "°C", lambda d: d[0] - 40),
    0x49: ("Pédale d'accélérateur D", "%", lambda d: d[0] * 100 / 255),
    0x5C: ("Température d'huile", "°C", lambda d: d[0] - 40),
    0x5E: ("Consommation instantanée", "L/h", lambda d: (d[0] * 256 + d[1]) / 20),
    0x7A: ("FAP : pression différentielle (banc 1)", "brut", lambda d: list(d)),
    0x7C: ("FAP : température", "brut", lambda d: list(d)),
    0xA6: ("Kilométrage", "km", lambda d: int.from_bytes(d[:4], "big") / 10),
}


def decoder_pid(pid: int, donnees: bytes):
    """(nom, valeur, unité) d'un PID connu, ou les octets bruts pour les autres."""
    if pid in PIDS and donnees:
        nom, unite, f = PIDS[pid]
        try:
            v = f(donnees)
            return nom, round(v, 2) if isinstance(v, float) else v, unite
        except IndexError:
            pass
    return f"PID {pid:02X} (non décodé)", donnees.hex(" ").upper(), "brut"


def decoder_codes(charge: bytes) -> list[str]:
    """Codes défaut (ex. P0401) d'une réponse aux modes 03, 07 ou 0A sur CAN."""
    octets = charge[2:] if len(charge) % 2 == 0 else charge[1:]
    codes = []
    for i in range(0, len(octets) - 1, 2):
        a, b = octets[i], octets[i + 1]
        if a == 0 and b == 0:
            continue
        codes.append("PCBU"[a >> 6] + f"{(a >> 4) & 3}{a & 0xF:X}{b:02X}")
    return codes


def masquer_vin(vin: str) -> str:
    """Cache le numéro de série (6 derniers caractères) : le VIN complet identifie la voiture."""
    return vin[:-6] + "******" if len(vin) == 17 else vin


# ------------------------------------------------------------------ séances

class Seance:
    """Une exécution du programme : connexion, journal, résultats enregistrés dans un dossier horodaté."""

    def __init__(self, args, nom: str):
        self.racine = Path(args.dossier)
        self.dossier = self.racine / f"{dt.datetime.now():%Y%m%d-%H%M%S}-{nom}"
        self.dossier.mkdir(parents=True, exist_ok=True)
        self.journal = Journal(self.dossier / "journal.txt")
        if args.simulation:
            transport = TransportSimulation()
        else:
            mac = args.mac or trouver_mac_vlinker()
            if not mac:
                sys.exit("vLinker introuvable : appaire « vLinker MC-Android » (code 1234) dans Windows, ou passe --mac.")
            print(f"Connexion Bluetooth à {mac}…")
            transport = TransportRfcomm(mac)
        self.transport = transport
        self.elm = Elm(transport, self.journal)
        self.resultats: dict = {"date": dt.datetime.now().isoformat(timespec="seconds"), "simulation": bool(args.simulation)}

    def enregistrer(self):
        """Écrit les résultats en JSON à côté du journal."""
        (self.dossier / "resultats.json").write_text(json.dumps(self.resultats, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Résultats : {self.dossier}")

    def fermer(self):
        """Ferme la liaison avec le boîtier."""
        self.transport.fermer()


def pids_geres(elm: Elm, mode: int = 0x01) -> list[int]:
    """Liste des PID que le calculateur moteur déclare gérer (tables 00, 20, 40…)."""
    geres, base = [], 0x00
    while base <= 0xE0:
        rep = elm.demander(f"{mode:02X}{base:02X}")
        charge = next((c for c in rep.values() if len(c) >= 6 and c[0] == mode + 0x40 and c[1] == base), None)
        if not charge:
            break
        bits = int.from_bytes(charge[2:6], "big")
        geres += [base + i + 1 for i in range(32) if bits & (1 << (31 - i))]
        if not bits & 1:
            break
        base += 0x20
    return [p for p in geres if p % 0x20]


def cmd_connexion(s: Seance, args):
    """Vérifie que le boîtier répond et que la voiture est joignable."""
    s.resultats["boitier"] = s.elm.initialiser()
    s.elm.viser(0x7DF, None)
    s.resultats["calculateur_moteur_repond"] = bool(s.elm.demander("0100", 5.0))
    print(json.dumps(s.resultats, ensure_ascii=False, indent=2))


def cmd_standard(s: Seance, args):
    """OBD normalisé : PID gérés et leurs valeurs, identification, codes défaut."""
    s.resultats["boitier"] = s.elm.initialiser()
    s.elm.viser(0x7DF, None)
    geres = pids_geres(s.elm)
    s.resultats["pids_geres"] = [f"{p:02X}" for p in geres]
    valeurs = {}
    for p in geres:
        rep = s.elm.demander(f"01{p:02X}")
        for entete, c in rep.items():
            if len(c) >= 2 and c[0] == 0x41 and c[1] == p:
                nom, v, unite = decoder_pid(p, c[2:])
                valeurs[f"{p:02X}"] = {"nom": nom, "valeur": v, "unite": unite, "calculateur": f"{entete:03X}"}
    s.resultats["valeurs"] = valeurs
    info = {}
    for p in [p for p in pids_geres(s.elm, 0x09) if p in (0x02, 0x04, 0x0A)] or [0x02]:
        rep = s.elm.demander(f"09{p:02X}", 4.0)
        for c in rep.values():
            if len(c) > 3 and c[0] == 0x49 and c[1] == p:
                texte = c[3:].decode("ascii", "replace").strip("\x00 ")
                info[{0x02: "vin", 0x04: "calibration", 0x0A: "nom_calculateur"}[p]] = masquer_vin(texte) if p == 2 else texte
    s.resultats["identification"] = info
    codes = {}
    for mode, nom in ((0x03, "memorises"), (0x07, "en_attente"), (0x0A, "permanents")):
        rep = s.elm.demander(f"{mode:02X}", 4.0)
        codes[nom] = {f"{h:03X}": decoder_codes(c) for h, c in rep.items() if c and c[0] == mode + 0x40}
    s.resultats["codes_defaut"] = codes
    print(json.dumps({k: s.resultats[k] for k in ("pids_geres", "identification", "codes_defaut")}, ensure_ascii=False, indent=2))
    for p, v in valeurs.items():
        print(f"  {p} {v['nom']} : {v['valeur']} {v['unite']}")


def sonder(elm: Elm, requete: int, reponse: int) -> dict | None:
    """Le calculateur répond-il ? Essaie le maintien de session puis l'identification (lectures)."""
    elm.viser(requete, reponse)
    for essai in ("3E00", "10C0", "1A80", "2180", "22F190"):
        rep = elm.demander(essai, 1.0).get(reponse)
        if rep:
            return {"premiere_reponse_a": essai, "reponse": rep.hex(" ").upper()}
    return None


def lire_identifiants_21(elm: Elm, reponse: int) -> dict[str, str]:
    """Lit tous les identifiants locaux 00 à FF (service 21) et garde ceux qui répondent positivement."""
    trouves, dernier_maintien = {}, time.monotonic()
    for lid in range(0x100):
        if time.monotonic() - dernier_maintien > 2:
            elm.demander("3E00", 0.5)
            dernier_maintien = time.monotonic()
        c = elm.demander(f"21{lid:02X}", 1.0).get(reponse)
        if c and c[0] == 0x61 and len(c) > 1 and c[1] == lid:
            trouves[f"21{lid:02X}"] = c[2:].hex(" ").upper()
    return trouves


def cmd_psa(s: Seance, args):
    """Cherche les calculateurs PSA qui répondent, puis lit tous leurs identifiants (lecture seule)."""
    s.resultats["boitier"] = s.elm.initialiser()
    trouves = {}
    for nom, req, rep in CALCULATEURS_PSA:
        print(f"Calculateur {nom} ({req:03X} → {rep:03X})…", end=" ", flush=True)
        sonde = sonder(s.elm, req, rep)
        if not sonde:
            print("pas de réponse")
            continue
        print("répond ; lecture des identifiants…", end=" ", flush=True)
        s.elm.demander("10C0", 1.0)
        ident = {k: s.elm.demander(k, 1.5).get(rep, b"").hex(" ").upper() for k in ("1A80", "1A81", "1A86")}
        ids = lire_identifiants_21(s.elm, rep)
        print(f"{len(ids)} identifiants")
        trouves[nom] = {"requete": f"{req:03X}", "reponse": f"{rep:03X}", "sonde": sonde,
                        "identification": {k: v for k, v in ident.items() if v}, "identifiants_21": ids}
    s.resultats["calculateurs"] = trouves
    (s.racine / "calculateurs.json").write_text(json.dumps(trouves, ensure_ascii=False, indent=2), encoding="utf-8")


def charger_calculateurs(racine: Path) -> dict:
    """Calculateurs et identifiants trouvés par la dernière commande `psa`."""
    chemin = racine / "calculateurs.json"
    if not chemin.exists():
        sys.exit("Lance d'abord la commande « psa ».")
    return json.loads(chemin.read_text(encoding="utf-8"))


def cmd_instantane(s: Seance, args):
    """Relit tous les identifiants connus et enregistre leurs valeurs sous un nom (ex. phares_allumes)."""
    s.elm.initialiser()
    photo = {}
    for nom, calc in charger_calculateurs(s.racine).items():
        req, rep = int(calc["requete"], 16), int(calc["reponse"], 16)
        s.elm.viser(req, rep)
        s.elm.demander("10C0", 1.0)
        photo[nom] = {}
        for ident in calc["identifiants_21"]:
            c = s.elm.demander(ident, 1.0).get(rep)
            if c and c[0] == 0x61:
                photo[nom][ident] = c[2:].hex(" ").upper()
    dossier = s.racine / "instantanes"
    dossier.mkdir(exist_ok=True)
    (dossier / f"{args.nom}.json").write_text(json.dumps(photo, ensure_ascii=False, indent=2), encoding="utf-8")
    s.resultats["instantane"] = args.nom
    print(f"Instantané « {args.nom} » : {sum(len(v) for v in photo.values())} identifiants relus")


def comparer_instantanes(a: dict, b: dict) -> list[dict]:
    """Octets qui diffèrent entre deux instantanés (calculateur, identifiant, position, avant, après)."""
    diffs = []
    for calc, ids in a.items():
        for ident, va in ids.items():
            vb = b.get(calc, {}).get(ident)
            if vb is None or vb == va:
                continue
            oa, ob = bytes.fromhex(va), bytes.fromhex(vb)
            for i in range(max(len(oa), len(ob))):
                x = oa[i] if i < len(oa) else None
                y = ob[i] if i < len(ob) else None
                if x != y:
                    diffs.append({"calculateur": calc, "identifiant": ident, "octet": i,
                                  "avant": None if x is None else f"{x:02X}", "apres": None if y is None else f"{y:02X}"})
    return diffs


def cmd_comparer(args):
    """Affiche les octets qui ont changé entre deux instantanés, et les identifiants lus d'un seul côté."""
    d = Path(args.dossier) / "instantanes"
    a = json.loads((d / f"{args.nom1}.json").read_text(encoding="utf-8"))
    b = json.loads((d / f"{args.nom2}.json").read_text(encoding="utf-8"))
    diffs = comparer_instantanes(a, b)
    for x in diffs:
        print(f"{x['calculateur']} {x['identifiant']} octet {x['octet']} : {x['avant']} → {x['apres']}")
    if not diffs:
        print("Aucune différence.")
    for calc in sorted(set(a) | set(b)):
        seuls_a = sorted(set(a.get(calc, {})) - set(b.get(calc, {})))
        seuls_b = sorted(set(b.get(calc, {})) - set(a.get(calc, {})))
        if seuls_a:
            print(f"{calc} : lus seulement dans {args.nom1} : {' '.join(seuls_a)}")
        if seuls_b:
            print(f"{calc} : lus seulement dans {args.nom2} : {' '.join(seuls_b)}")
    return diffs


PLAGES_22 = "0000-03FF,1000-13FF,2000-23FF,3000-33FF,D000-D4FF,E000-E3FF,F100-F1FF,F400-F4FF"


def lire_plages(texte: str) -> list[tuple[int, int]]:
    """« 0000-00FF,F190 » → [(0x0000, 0x00FF), (0xF190, 0xF190)]."""
    plages = []
    for morceau in texte.replace(" ", "").split(","):
        if morceau:
            debut, _, fin = morceau.partition("-")
            plages.append((int(debut, 16), int(fin or debut, 16)))
    return plages


def trouver_calculateur(texte: str) -> tuple[str, int, int]:
    """Calculateur PSA dont le nom contient le texte donné (ex. « moteur », « abs »)."""
    for nom, req, rep in CALCULATEURS_PSA:
        if texte.lower() in nom.lower():
            return nom, req, rep
    sys.exit(f"Calculateur inconnu : {texte}. Choix : " + ", ".join(n for n, _, _ in CALCULATEURS_PSA))


def ouvrir_session(elm: Elm, reponse: int) -> str | None:
    """Ouvre la session de diagnostic étendue PSA (C0), sinon UDS (03) ; renvoie celle acceptée."""
    for session in ("10C0", "1003"):
        c = elm.demander(session, 1.0).get(reponse)
        if c and c[0] == 0x50:
            return session
    return None


def balayer_identifiants(elm: Elm, reponse: int, service: int, identifiants: list[int],
                         afficher=print) -> tuple[dict[str, str], dict[str, int]]:
    """Lit chaque identifiant (21xx ou 22xxxx) ; renvoie ceux qui répondent et le compte des refus par code.

    S'arrête tôt si le calculateur dit ne pas connaître le service (refus 11 trois fois de suite)."""
    largeur = 2 if service == 0x21 else 4
    trouves, refus = {}, {}
    dernier_maintien, refus_11_suite = time.monotonic(), 0
    for n, ident in enumerate(identifiants, 1):
        if time.monotonic() - dernier_maintien > 2:
            elm.demander("3E00", 0.5)
            dernier_maintien = time.monotonic()
        hexa = f"{service:02X}{ident:0{largeur}X}"
        c = elm.demander(hexa, 1.0).get(reponse)
        if c and c[0] == service + 0x40 and c[1:1 + largeur // 2] == ident.to_bytes(largeur // 2, "big"):
            trouves[hexa] = c[1 + largeur // 2:].hex(" ").upper()
            afficher(f"  {hexa} : {trouves[hexa]}")
            refus_11_suite = 0
        elif c and len(c) >= 3 and c[0] == 0x7F:
            code = f"{c[2]:02X}"
            refus[code] = refus.get(code, 0) + 1
            refus_11_suite = refus_11_suite + 1 if c[2] == 0x11 else 0
            if refus_11_suite >= 3:
                afficher(f"  le calculateur ne connaît pas le service {service:02X} (refus 11)")
                break
        else:
            refus["sans réponse"] = refus.get("sans réponse", 0) + 1
        if n % 256 == 0:
            afficher(f"  … {n}/{len(identifiants)} lus, {len(trouves)} trouvés")
    return trouves, refus


def cmd_balayer(s: Seance, args):
    """Lit des plages d'identifiants d'un calculateur et les enregistre comme un instantané."""
    nom, req, rep = trouver_calculateur(args.calculateur)
    service = int(args.service, 16)
    dossier = s.racine / "instantanes"
    if args.relire:
        photo = json.loads((dossier / f"{args.relire}.json").read_text(encoding="utf-8")).get(nom, {})
        identifiants = [int(k[2:], 16) for k in photo if int(k[:2], 16) == service]
    else:
        plages = lire_plages(args.plages or ("00-FF" if service == 0x21 else PLAGES_22))
        identifiants = [i for debut, fin in plages for i in range(debut, fin + 1)]
    s.resultats["boitier"] = s.elm.initialiser()
    s.elm.viser(req, rep)
    session = ouvrir_session(s.elm, rep)
    print(f"{nom} : session {session or 'non ouverte'} ; {len(identifiants)} identifiants {service:02X} à lire…")
    trouves, refus = balayer_identifiants(s.elm, rep, service, identifiants)
    print(f"{len(trouves)} identifiants répondent ; refus : {refus}")
    dossier.mkdir(exist_ok=True)
    (dossier / f"{args.nom}.json").write_text(json.dumps({nom: trouves}, ensure_ascii=False, indent=2), encoding="utf-8")
    s.resultats.update({"calculateur": nom, "service": f"{service:02X}", "session": session,
                        "identifiants": trouves, "refus": refus, "instantane": args.nom})


def chercher_valeur(instantanes: dict[str, dict], km: int) -> list[dict]:
    """Emplacements où le kilométrage apparaît (km, hm ou ×10, sur 2 à 4 octets, gros-boutiste)."""
    cibles = {f"km x{f}": km * f for f in (1, 10)} | {"km /10": km // 10}
    trouves = []
    for nom_inst, photo in instantanes.items():
        for calc, ids in photo.items():
            for ident, hexa in ids.items():
                o = bytes.fromhex(hexa)
                for taille in (2, 3, 4):
                    for i in range(len(o) - taille + 1):
                        v = int.from_bytes(o[i:i + taille], "big")
                        for etiquette, cible in cibles.items():
                            if cible and abs(v - cible) <= max(2, cible // 10000):
                                trouves.append({"instantane": nom_inst, "calculateur": calc, "identifiant": ident,
                                                "octet": i, "taille": taille, "valeur": v, "lecture": etiquette})
    return trouves


def cmd_chercher_km(args):
    """Cherche le kilométrage lu sur le compteur dans tous les instantanés enregistrés."""
    d = Path(args.dossier) / "instantanes"
    instantanes = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(d.glob("*.json"))}
    trouves = chercher_valeur(instantanes, args.km)
    for t in trouves:
        print(f"{t['calculateur']} {t['identifiant']} octets {t['octet']}..{t['octet'] + t['taille'] - 1} "
              f"= {t['valeur']} ({t['lecture']}, instantané {t['instantane']})")
    if not trouves:
        print("Kilométrage introuvable dans les identifiants lus.")
    return trouves


def cmd_direct(s: Seance, args):
    """Relève en continu les PID normalisés gérés (CSV), pour voir EGR, turbo, etc. bouger en roulant."""
    s.elm.initialiser()
    s.elm.viser(0x7DF, None)
    geres = [p for p in pids_geres(s.elm) if p in PIDS]
    csv = s.dossier / "direct.csv"
    csv.write_text("heure;" + ";".join(PIDS[p][0] for p in geres) + "\n", encoding="utf-8")
    fin = time.monotonic() + args.minutes * 60
    n = 0
    while time.monotonic() < fin:
        ligne = [dt.datetime.now().strftime("%H:%M:%S")]
        for p in geres:
            c = next((c for c in s.elm.demander(f"01{p:02X}", 1.0).values() if len(c) > 2 and c[1] == p), None)
            ligne.append("" if not c else str(decoder_pid(p, c[2:])[1]))
        with csv.open("a", encoding="utf-8") as f:
            f.write(";".join(ligne) + "\n")
        n += 1
        if args.simulation and n >= 3:
            break
    s.resultats["mesures"] = n
    print(f"{n} relevés dans {csv}")


def main(argv=None):
    """Point d'entrée : analyse la ligne de commande et lance la séance demandée."""
    p = argparse.ArgumentParser(description="Explorateur OBD en lecture seule (Peugeot 308 T7, vLinker MC+).")
    p.add_argument("--simulation", action="store_true", help="fausse voiture, sans boîtier")
    p.add_argument("--mac", help="adresse Bluetooth du vLinker (sinon trouvée dans Windows)")
    p.add_argument("--dossier", default=str(DOSSIER_DEFAUT), help="dossier des journaux")
    sp = p.add_subparsers(dest="commande", required=True)
    sp.add_parser("connexion")
    sp.add_parser("standard")
    sp.add_parser("psa")
    i = sp.add_parser("instantane")
    i.add_argument("--nom", required=True)
    c = sp.add_parser("comparer")
    c.add_argument("nom1")
    c.add_argument("nom2")
    k = sp.add_parser("chercher-km")
    k.add_argument("km", type=int)
    d = sp.add_parser("direct")
    d.add_argument("--minutes", type=float, default=3)
    b = sp.add_parser("balayer")
    b.add_argument("--calculateur", required=True, help="ex. moteur, abs")
    b.add_argument("--service", choices=("21", "22"), default="22")
    b.add_argument("--plages", help="ex. 0000-00FF,F190 (par défaut : 00-FF pour 21, plages courantes pour 22)")
    b.add_argument("--relire", help="ne relire que les identifiants de cet instantané")
    b.add_argument("--nom", required=True, help="nom de l'instantané enregistré")
    args = p.parse_args(argv)

    if args.commande == "comparer":
        return cmd_comparer(args)
    if args.commande == "chercher-km":
        return cmd_chercher_km(args)
    s = Seance(args, args.commande)
    try:
        {"connexion": cmd_connexion, "standard": cmd_standard, "psa": cmd_psa,
         "instantane": cmd_instantane, "direct": cmd_direct, "balayer": cmd_balayer}[args.commande](s, args)
    finally:
        s.enregistrer()
        s.fermer()
    return s.resultats


if __name__ == "__main__":
    main()
