#!/bin/zsh
# Récupère les journaux GPS du Lanceur 308 sur la clé USB et les analyse.
# Usage : branche la clé sur le Mac, puis lance ~/autoradio-308/analyser_gps.sh
#         (ou ~/autoradio-308/analyser_gps.sh /chemin/vers/le/dossier/gps)
DEST=~/autoradio-308/logs/gps
SRC=${1:-}
if [ -z "$SRC" ]; then
  for v in /Volumes/*; do
    [ -d "$v/Android/data/fr.p308.lanceur/files/gps" ] && SRC="$v/Android/data/fr.p308.lanceur/files/gps"
  done
fi
[ -n "$SRC" ] || { echo "Clé introuvable : dans le Lanceur 308, va dans Véhicule → GPS → « Copier le journal sur la clé USB », puis rebranche la clé sur le Mac."; exit 1; }
mkdir -p $DEST && cp "$SRC"/*.csv $DEST/ && echo "Journaux copiés dans $DEST"
echo
python3 -I ~/autoradio-308/outils/analyse_gps.py $DEST
