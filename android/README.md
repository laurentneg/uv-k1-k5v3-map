# UV-K1 Map — app Android (Kotlin)

Application native qui remplace la PWA sur téléphone : **GPS** + **USB OTG CDC**
vers le UV-K1 (protocole K5Viewer) + carte **OpenStreetMap** (osmdroid).

Web Serial ne fonctionne pas de façon fiable sur Chrome Android ; cette app
utilise `usb-serial-for-android` (CDC ACM).

## Fonctions

- Inject GPS téléphone → radio (`AA 55 07`, TTL firmware 15 s)
- Keepalive `55 AA 00 00` toutes les 200 ms + **DTR/RTS** (obligatoire pour que la radio émette)
- Réception des cibles décodées (`AA 55 08`) → marqueurs OSM + **trace ballon** (sondes)
- Lien **USB-C CDC** ou **câble K** (CH340/CP210x/FTDI/PL2303)
- UI compacte : carte plein écran, **Journal** pour logs / cibles

## Prérequis

- Android 8+ (API 26), port USB OTG **data**
- Firmware Fusion API level 5 + Sonde M20 ≥ 1.6 (ou autre app branchée)
- Fermer UV Studio / tout autre client série

## Build avec Android Studio

1. Ouvrir le dossier `android/` dans Android Studio (Ladybug+ / AGP 8.7).
2. Laisser Gradle synchroniser (SDK 35).
3. Brancher le téléphone en mode développeur, **Run**.

## Build en ligne de commande

```sh
cd android
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Variables utiles :

```sh
export ANDROID_HOME=~/Android/Sdk
export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

## Usage terrain

1. Installer l’APK, autoriser **Localisation**.
2. Choisir le **Lien** :
   - **Auto** — CDC USB-C si présent, sinon câble de prog (prise K)
   - **USB-C (CDC)** — prise USB du K1 (pratique, parfois bruyant en RF)
   - **Câble K (prog)** — OTG → câble Kenwood/Baofeng CH340/CP210x/FTDI/PL2303 → jack K
3. Brancher, accepter la permission USB, **Lier**.
4. Lancer Sonde M20 (ou autre) : marqueurs + traces sur la carte ; détails via **Journal**.

Le firmware parle le même protocole K5Viewer (38400) sur USB-C et sur l’UART
de la prise K. Le câble K évite souvent le bruit S9 du téléphone en OTG USB-C.

## Structure

| Fichier | Rôle |
|---------|------|
| `K5ViewerProtocol.kt` | Paquets `0x07` / `0x08`, parseur |
| `UsbRadioLink.kt` | CDC, permission USB, keepalive |
| `PhoneGps.kt` | LocationManager + réinject 8 s |
| `MainActivity.kt` | UI, osmdroid, orchestration |

La PWA web dans le dépôt parent reste utile sur **PC**.
