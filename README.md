# UV-K1 Map

PWA Android (Chrome) qui :

1. lit le **GPS du téléphone** ;
2. l’injecte dans le UV-K1 via **Web Serial** (protocole K5Viewer `0x07`) ;
3. affiche sur **OpenStreetMap** les positions décodées renvoyées par le firmware (`0x08`) — Sonde M20, autres sondes, EPIRB 406, APRS RX.

Le shell de l’application et les tuiles OSM déjà visitées restent utilisables hors ligne (Service Worker).

## Prérequis

- Chrome ou Edge sur **Android** (Web Serial + Geolocation)
- Câble **USB OTG data** vers le UV-K1 / UV-K5 V3
- Firmware Fusion avec K5Viewer et **API overlay niveau 5** (`report_position`)
- App **Sonde M20** ≥ 1.6 (ou autre decodeur branché) flashée sur la radio
- Fermer **UV Studio**, Tab5 K1Viewer et tout autre client série

## Utilisation

Servir le dossier en HTTPS ou en `localhost` (requis pour GPS + Serial) :

```sh
cd uv-k1-map
python3 -m http.server 8080
```

Sur le téléphone : ouvrir l’URL (via le LAN), autoriser la localisation, **Connecter radio**, choisir le CDC du K1 (`36B7:FFFF` typiquement).

- Le GPS est renvoyé au moins toutes les 8 s (TTL firmware 15 s) → GeoNav / APRS TX.
- Chaque decode M20 (et apps branchées) apparaît comme marqueur + entrée dans **Cibles**.
- **Cache tuiles** précharge une zone autour du téléphone pour un usage offline ultérieur.

## Protocole

| Direction | Trame |
|-----------|--------|
| Host → radio | `55 AA 00 00` keepalive (~200 ms) |
| Host → radio | `AA 55 07` + lat/lon `int32` LE centi-arcsecondes + XOR |
| Radio → host | `AA 55 08` + label[12] + lat + lon + alt + source + XOR |

`source` : 1 = sonde, 2 = EPIRB, 3 = APRS, 0 = autre.

## Développement

Pas de build : HTML/CSS/JS ES modules + Leaflet vendored sous `vendor/leaflet/`.

Le dépôt firmware compagnon est `uv-k1-k5v3-firmware-custom` (API level 5 + hooks apps).
