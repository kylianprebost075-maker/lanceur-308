"""Tests de l'explorateur OBD : liste blanche de lecture, décodage, et séances complètes en simulation."""
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import obd_explorer as o


class ListeBlanche(unittest.TestCase):
    """Seules les lectures passent ; tout ce qui modifie la voiture est refusé."""

    def test_lectures_permises(self):
        for c in ("ATZ", "ATSH6A8", "ATCRA688", "ATFCSD300000", "0100", "010C", "03", "07", "0A", "0902",
                  "2180", "21FF", "22F190", "1A80", "3E00", "10C0", "1081", "1003", "1902FF", "18 00 FF 00", "@1"):
            self.assertTrue(o.commande_autorisee(c), c)

    def test_ecritures_refusees(self):
        for c in ("04",          # effacement des codes OBD
                  "14FFFFFF",    # effacement des codes (diagnostic)
                  "2EF19001",    # écriture d'identifiant
                  "3B9001",      # écriture KWP
                  "2F123403",    # pilotage d'entrée/sortie
                  "30010101",    # pilotage KWP
                  "31010203",    # routine (ex. régénération FAP)
                  "2701",        # accès sécurisé
                  "1101",        # remise à zéro du calculateur
                  "1002", "1085",  # session de programmation
                  "34", "36", "3D", "85",
                  "1904",        # sous-fonction 19 non prévue
                  "ATPP0CSV19", "ATBRD23", "ATMA", "ATCSM1",
                  "", "ZZ", "123"):
            self.assertFalse(o.commande_autorisee(c), c)

    def test_elm_n_envoie_jamais_une_commande_refusee(self):
        envoyees = []

        class Espion:
            def echanger(self, texte, delai):
                envoyees.append(texte)
                return "OK"

        with tempfile.TemporaryDirectory() as d:
            elm = o.Elm(Espion(), o.Journal(Path(d) / "j.txt"))
            with self.assertRaises(o.CommandeRefusee):
                elm.brut("04")
            self.assertEqual(envoyees, [])
            self.assertIn("REFUS 04", (Path(d) / "j.txt").read_text(encoding="utf-8"))


class Decodage(unittest.TestCase):
    """Trames ISO-TP, PID et codes défaut."""

    def test_reassemblage_multi_trames(self):
        lignes = ["7E8 10 14 49 02 01 56 46 33", "7E8 21 53 49 4D 55 4C 41 54", "7E8 22 49 4F 30 30 30 30 31"]
        charge = o.reassembler(lignes)[0x7E8]
        self.assertEqual(charge[3:].decode(), "VF3SIMULATIO00001")

    def test_reponse_en_preparation_ignoree(self):
        rep = o.reassembler(["688 03 7F 21 78", "688 04 61 A0 01 02"])
        self.assertEqual(rep[0x688], bytes([0x61, 0xA0, 0x01, 0x02]))

    def test_pid_et_codes(self):
        self.assertEqual(o.decoder_pid(0x0C, bytes([0x0D, 0xAC]))[1], 875)
        self.assertEqual(o.decoder_pid(0x05, bytes([0x7B]))[1], 83)
        self.assertEqual(o.decoder_codes(bytes([0x43, 0x02, 0x04, 0x01, 0x24, 0x02])), ["P0401", "P2402"])
        self.assertEqual(o.decoder_codes(bytes([0x47, 0x00])), [])

    def test_vin_masque(self):
        self.assertEqual(o.masquer_vin("VF3SIMULATIO00001"), "VF3SIMULATIO*****"[:11] + "******")


class SeancesSimulees(unittest.TestCase):
    """Toutes les commandes tournent de bout en bout sur la fausse voiture."""

    def lancer(self, d, *args):
        with redirect_stdout(io.StringIO()):
            return o.main(["--simulation", "--dossier", d, *args])

    def test_parcours_complet(self):
        with tempfile.TemporaryDirectory() as d:
            r = self.lancer(d, "standard")
            self.assertEqual(r["valeurs"]["0C"]["valeur"], 875)
            self.assertIn("2C", r["pids_geres"])  # vanne EGR
            self.assertEqual(r["codes_defaut"]["memorises"], {"7E8": ["P0401"]})
            self.assertTrue(r["identification"]["vin"].endswith("******"))

            r = self.lancer(d, "psa")
            self.assertEqual(set(r["calculateurs"]), {"Moteur (CMM, EDC16C34)", "BSI (boîtier de servitude)"})
            self.assertIn("21A0", r["calculateurs"]["Moteur (CMM, EDC16C34)"]["identifiants_21"])

            self.lancer(d, "instantane", "--nom", "phares_eteints")
            ancien = o.TransportSimulation.__init__

            def phares_allumes(self):
                ancien(self)
                self.phares = True

            o.TransportSimulation.__init__ = phares_allumes
            try:
                self.lancer(d, "instantane", "--nom", "phares_allumes")
            finally:
                o.TransportSimulation.__init__ = ancien
            diffs = self.lancer(d, "comparer", "phares_eteints", "phares_allumes")
            self.assertEqual([(x["identifiant"], x["octet"], x["apres"]) for x in diffs], [("2120", 0, "05")])

            km = self.lancer(d, "chercher-km", "123456")
            self.assertTrue(any(t["identifiant"] == "2110" and t["taille"] == 3 for t in km))

            r = self.lancer(d, "direct", "--minutes", "1")
            self.assertEqual(r["mesures"], 3)
            journal = next(Path(d).glob("*-standard/journal.txt")).read_text(encoding="utf-8")
            self.assertNotIn("REFUS", journal)
            self.assertTrue(json.loads(next(Path(d).glob("*-psa/resultats.json")).read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
