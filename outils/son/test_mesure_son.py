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


if __name__ == "__main__":
    unittest.main()
