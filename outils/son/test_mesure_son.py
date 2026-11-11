"""Tests de l'analyse (signaux synthétiques, sans micro ni autoradio)."""
import unittest

import numpy as np

import mesure_son as m

R = m.RATE


def rose(n, seed=0):
    """Bruit rose par filtrage du spectre d'un bruit blanc (1/sqrt(f))."""
    rng = np.random.default_rng(seed)
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / R)
    f[0] = f[1]
    x = np.fft.irfft(spec / np.sqrt(f), n)
    return x / np.abs(x).max() * 0.5


class Courbe(unittest.TestCase):
    def test_bruit_rose_plat_par_tiers(self):
        niv = m.niveaux_tiers(rose(R * 10))
        mid = [v for f, v in niv.items() if 63 <= f <= 10000]
        self.assertLess(max(mid) - min(mid), 3.0)

    def test_bosse_compensee_par_egaliseur(self):
        niv = {f: 0.0 for f in m.TIERS}
        for f in (100, 125, 160):
            niv[f] = 8.0  # bosse de 8 dB dans le bas-médium
        ec = m.ecarts({f: v + m.cible_voiture(f) for f, v in niv.items()})
        eq = m.reglages_eq(ec)
        self.assertLess(eq[125], -4)
        self.assertAlmostEqual(eq[1000], 0, delta=0.5)

    def test_montee_limitee(self):
        ec = {f: -20.0 for f in m.TIERS}
        self.assertTrue(all(v <= 4.0 for v in m.reglages_eq(ec).values()))


class Clics(unittest.TestCase):
    def signal(self, decalage_ms):
        x = np.random.default_rng(1).standard_normal(int(R * 6.2)) * 0.002
        clic = np.hanning(48) * 0.6
        for k in range(4):
            g = int((0.3 + 1.5 * k) * R)
            d = g + int((0.5 + decalage_ms / 1000) * R)
            x[g:g + 48] += clic
            x[d:d + 48] += clic
        return x

    def test_ecart_mesure(self):
        for dec in (0.0, 1.5, -2.0):
            ms, sd, n = m.ecart_gauche_droite(m.detecter_clics(self.signal(dec)))
            self.assertEqual(n, 4)
            self.assertAlmostEqual(ms, dec, delta=0.1)

    def test_bruit_seul_aucun_clic(self):
        x = np.random.default_rng(2).standard_normal(R * 5) * 0.001
        self.assertEqual(m.detecter_clics(x), [])

    def test_rien(self):
        self.assertIsNone(m.ecart_gauche_droite([0.0, 1.0, 2.0]))


class Saturation(unittest.TestCase):
    def test_thd_sinus_pur_et_ecrete(self):
        t = np.arange(R) / R
        s = np.sin(2 * np.pi * 400 * t)
        self.assertLess(m.thd(s * 0.5), 0.5)
        self.assertGreater(m.thd(np.clip(s, -0.6, 0.6)), 3.0)

    def test_paliers_et_premier_qui_sature(self):
        t = np.arange(2 * R) / R
        s = np.sin(2 * np.pi * 400 * t)
        parts = [np.zeros(R * 3)]
        for k in range(5):
            a = 0.1 * (k + 1)
            seg = s * a if k < 3 else np.clip(s * 0.9, -0.5, 0.5)
            parts += [seg, np.zeros(R)]
        res = m.analyse_saturation(np.concatenate(parts))
        self.assertEqual(len(res), 5)
        self.assertEqual([r["trop"] for r in res], [False, False, False, True, True])


class Fyt(unittest.TestCase):
    def test_quinze_bandes_et_prudence(self):
        ec = {f: 0.0 for f in m.TIERS}
        for f in (63, 80):
            ec[f] = 9.0  # bosse
        ec[25] = -15.0   # creux tout en bas
        eq = m.reglages_fyt(ec)
        self.assertEqual(len(eq), 15)
        self.assertLessEqual(eq[63], -7)
        self.assertEqual(eq[25], 0.0, "jamais de montée sous 40 Hz")
        self.assertEqual(eq[1000], 0.0)
        self.assertTrue(all(v == round(v) for v in eq.values()), "pas de 1 dB")

    def test_bosse_basses(self):
        ec = {f: 0.0 for f in m.TIERS}
        ec[100] = 5.0
        ec[125] = 7.0
        self.assertEqual(m.bosse_basses(ec), 125)
        self.assertIsNone(m.bosse_basses({f: 1.0 for f in m.TIERS}))


class Vibration(unittest.TestCase):
    def montee(self, vibre_a=None):
        """Montée 30 → 150 Hz en 30 s ; si vibre_a, grésillement large autour de cette fréquence."""
        t = np.arange(30 * R) / R
        f = 30 * 5 ** (t / 30)
        ph = 2 * np.pi * np.cumsum(f) / R
        x = 0.4 * np.sin(ph)
        rng = np.random.default_rng(3)
        x = x + rng.standard_normal(len(x)) * 0.0005  # bruit de fond
        if vibre_a:
            zone = np.abs(np.log2(f / vibre_a)) < 0.06
            buzz = rng.standard_normal(len(x))
            buzz = np.diff(buzz, prepend=0)  # bruit aigu (grésillement)
            x = x + np.where(zone, buzz * 0.05, 0)
        return np.concatenate([np.zeros(R), x, np.zeros(R)])

    def test_trouve_la_vibration(self):
        for hz in (55, 81, 120):
            got = m.frequence_vibration(self.montee(hz))
            self.assertIsNotNone(got)
            self.assertLess(abs(np.log2(got / hz)), 0.1, f"attendu {hz}, trouvé {got}")

    def test_rien_ne_vibre(self):
        self.assertIsNone(m.frequence_vibration(self.montee()))

    def test_silence(self):
        self.assertIsNone(m.frequence_vibration(np.zeros(R * 5)))


class EqFyt(unittest.TestCase):
    def test_lit_les_points_deplaces(self):
        from PIL import Image, ImageDraw
        import fyt_eq as f
        voulu = {b: v for b, v in zip(m.FYT15, [0, 3, -6, -8, 4, 0, -2, 0, 1, 0, 0, -12, 12, 0, 0])}
        img = Image.new("RGB", (1280, 720), (20, 30, 40))
        d = ImageDraw.Draw(img)
        for b, x in zip(m.FYT15, f.X):
            y = f.db_vers_y(voulu[b])
            d.ellipse([x - 5, y - 5, x + 5, y + 5], fill=(170, 230, 245))
        self.assertEqual(f.lire_points(img), voulu)

    def test_conversion(self):
        import fyt_eq as f
        self.assertEqual(f.db_vers_y(0), f.Y_ZERO)
        self.assertAlmostEqual(f.db_vers_y(12), f.Y_HAUT, delta=1)
        self.assertAlmostEqual(f.db_vers_y(-30), f.Y_BAS, delta=1)

class DeuxPlaces(unittest.TestCase):
    def test_moyenne_ponderee_et_fiabilite(self):
        import reglage_auto as r
        cond = ({100: 6.0, 1000: 0.0, 8000: -4.0}, {100: True, 1000: True, 8000: False}, 0.6)
        pas = ({100: -4.0, 1000: 2.0, 8000: -6.0}, {100: True, 1000: False, 8000: True}, 0.4)
        ec, fi = r.combiner([cond, pas])
        self.assertAlmostEqual(ec[100], 2.0)   # 0,6 x 6 + 0,4 x -4
        self.assertAlmostEqual(ec[1000], 0.0)  # seule la place conducteur est fiable
        self.assertAlmostEqual(ec[8000], -6.0)  # seule la place passager est fiable
        self.assertTrue(all(fi.values()))
        ec, fi = r.combiner([({50: 3.0}, {50: False}, 0.6), ({50: 1.0}, {50: False}, 0.4)])
        self.assertFalse(fi[50])

if __name__ == "__main__":
    unittest.main()
