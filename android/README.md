# UV-K1 Map — app Android (Kotlin)

Application native qui remplace la PWA sur téléphone : **GPS** + **USB OTG CDC**
vers le UV-K1 (protocole K5Viewer) + carte **OpenStreetMap** (osmdroid).

Web Serial ne fonctionne pas de façon fiable sur Chrome Android ; cette app
utilise `usb-serial-for-android` (CDC ACM).

## Fonctions

- Inject GPS téléphone → radio (`AA 55 07`, TTL firmware 15 s)
- Keepalive `55 AA 00 00` toutes les 200 ms
- Réception des cibles décodées (`AA 55 08`) → marqueurs OSM
- Filtre USB `36B7:FFFF` (Fusion CDC)

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
2. Brancher le UV-K1 via OTG ; accepter la permission USB.
3. **Connecter radio**.
4. Lancer Sonde M20 (ou autre) : les fixes apparaissent sous **Cibles**.

## Structure

| Fichier | Rôle |
|---------|------|
| `K5ViewerProtocol.kt` | Paquets `0x07` / `0x08`, parseur |
| `UsbRadioLink.kt` | CDC, permission USB, keepalive |
| `PhoneGps.kt` | LocationManager + réinject 8 s |
| `MainActivity.kt` | UI, osmdroid, orchestration |

La PWA web dans le dépôt parent reste utile sur **PC**.
