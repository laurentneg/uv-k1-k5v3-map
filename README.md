# UV-K1 Map

Outils pour injecter le GPS du téléphone dans le UV-K1 (K5Viewer `0x07`) et
afficher sur OpenStreetMap les positions décodées (`0x08`).

## App Android (recommandé sur téléphone)

Voir **[android/README.md](android/README.md)** — Kotlin natif, USB OTG CDC,
GPS, osmdroid. C’est le chemin terrain : Web Serial ne marche pas de façon
fiable sur Chrome Android.

## PWA (PC / desktop)

Les fichiers à la racine (`index.html`, `serial/`, …) restent une PWA pour
**Chrome/Edge desktop** + Web Serial.

```sh
python3 -m http.server 8080
```

## Protocole

| Direction | Trame |
|-----------|--------|
| Host → radio | `55 AA 00 00` keepalive (~200 ms) |
| Host → radio | `AA 55 07` + lat/lon `int32` LE centi-arcsecondes + XOR |
| Radio → host | `AA 55 08` + label[12] + lat + lon + alt + source + XOR |

`source` : 1 = sonde, 2 = EPIRB, 3 = APRS, 0 = autre.

Firmware compagnon : `uv-k1-k5v3-firmware-custom` (API level 5).
