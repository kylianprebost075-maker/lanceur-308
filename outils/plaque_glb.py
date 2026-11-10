"""Change la plaque d'immatriculation dessinée dans un modèle 3D .glb (Python pur, sans dépendance).

Sert à fabriquer le modèle « public » (plaque factice) à partir du modèle personnel, sans refaire tout l'assemblage.

  python plaque_glb.py --voir  car.glb sortie.png          -> extrait l'image de la plaque (pour vérifier)
  python plaque_glb.py car.glb nouvelle_plaque.png sortie.glb  -> écrit un modèle avec la nouvelle plaque

L'image doit être un PNG de 1040 x 220, dans le même sens que celle du modèle (voir --voir).
"""
import json
import struct
import sys
import zlib

PLACE = 131072  # octets réservés à l'image de la plaque dans le modèle


def lire(path):
    """Lit un .glb : renvoie (description JSON, données binaires)."""
    with open(path, "rb") as f:
        magic, ver, _ = struct.unpack("<4sII", f.read(12))
        assert magic == b"glTF" and ver == 2, "ce fichier n'est pas un .glb version 2"
        jlen, _ = struct.unpack("<II", f.read(8))
        gltf = json.loads(f.read(jlen).decode("utf-8"))
        blen, _ = struct.unpack("<II", f.read(8))
        return gltf, f.read(blen)


def ecrire(path, gltf, binaire):
    """Écrit un .glb (JSON complété par des espaces, binaire par des zéros, comme le veut le format)."""
    j = json.dumps(gltf, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    j += b" " * (-len(j) % 4)
    binaire += b"\0" * (-len(binaire) % 4)
    with open(path, "wb") as f:
        f.write(struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(j) + 8 + len(binaire)))
        f.write(struct.pack("<II", len(j), 0x4E4F534A) + j)
        f.write(struct.pack("<II", len(binaire), 0x004E4942) + binaire)


def vue_plaque(gltf):
    """Renvoie la zone de données (bufferView) qui contient l'image de la plaque."""
    images = gltf.get("images", [])
    nommees = [i for i in images if "plaque" in (i.get("name") or "").lower()]
    choix = nommees or images
    assert len(choix) == 1, f"image de plaque introuvable ou ambiguë ({len(images)} images dans le modèle)"
    return gltf["bufferViews"][choix[0]["bufferView"]]


def extraire(glb, png):
    gltf, binaire = lire(glb)
    v = vue_plaque(gltf)
    debut = v.get("byteOffset", 0)
    with open(png, "wb") as f:
        f.write(binaire[debut:debut + v["byteLength"]])


def remplacer(glb, png, sortie):
    gltf, binaire = lire(glb)
    v = vue_plaque(gltf)
    with open(png, "rb") as f:
        image = f.read()
    assert image[:8] == b"\x89PNG\r\n\x1a\n", "la nouvelle plaque doit être un fichier PNG"
    assert struct.unpack(">II", image[16:24]) == (1040, 220), "la plaque doit faire 1040 x 220"
    # Place réservée : l'image est allongée jusqu'à PLACE octets par un bloc PNG de remplissage (ignoré à
    # l'affichage). L'appli remplace ensuite la plaque sur place, sans rien décaler (voir ui/Plate.kt).
    manque = PLACE - len(image)
    assert manque >= 12, "image de plaque trop grosse"
    bloc = b"paDd" + b"\0" * (manque - 12)
    image = image[:-12] + struct.pack(">I", manque - 12) + bloc + struct.pack(">I", zlib.crc32(bloc)) + image[-12:]
    debut = v.get("byteOffset", 0)
    ancienne = v["byteLength"] + (-v["byteLength"] % 4)   # place occupée, alignée sur 4 octets
    nouvelle = image + b"\0" * (-len(image) % 4)
    decalage = len(nouvelle) - ancienne
    binaire = binaire[:debut] + nouvelle + binaire[debut + ancienne:]
    for autre in gltf["bufferViews"]:
        if autre is not v and autre.get("byteOffset", 0) > debut:
            autre["byteOffset"] += decalage
    v["byteLength"] = len(image)
    gltf["buffers"][0]["byteLength"] = len(binaire)
    ecrire(sortie, gltf, binaire)


if __name__ == "__main__":
    a = sys.argv[1:]
    if len(a) == 3 and a[0] == "--voir":
        extraire(a[1], a[2])
    elif len(a) == 3:
        remplacer(a[0], a[1], a[2])
    else:
        sys.exit(__doc__)
