# StarStacKadam

Client Android di **stack preview JPEG** (allinea + media MEAN + stretch) collegato al Raspberry che espone VesperaHelper via **Tailscale** (o LAN).

Package: `com.starstackadam` · versione 0.1

## Cosa fa (v0.1)

1. Si connette in FTP anonymous (PASV) all’Helper
2. Sfoglia una cartella e seleziona più JPEG/PNG (batch esplicito)
3. Scarica i frame, allinea le stelle al riferimento e accumula una **media streaming**
4. Mostra un’anteprima che cresce frame dopo frame (autostretch o livelli)
5. Opzionale: sottrazione fondo polinomiale (off di default)
6. Salva l’anteprima come PNG in `Pictures/StarStacKadam`

**Non** espone Median/Sigma in UI (restano nel codice engine).  
**Non** fa poll live sul telescopio.  
**Non** supporta FITS in v0.1.

## Porte FTP (VesperaHelper)

| Sorgente | Porta | Contenuto tipico |
|----------|------:|------------------|
| HD Helper | **2121** | Foto già sul disco del Pi / sync |
| Vespera   | **2122** | Storage telescopio (sempre multi-select batch) |

Host: indirizzo **Tailscale** del Pi (`100.x.y.z`) oppure hostname/LAN raggiungibile dal telefono.

PASV: se il server annuncia `0.0.0.0` o un IP privato non raggiungibile dal client, l’app usa l’host di controllo.

## Requisiti

- Android 10+ (minSdk 29)
- Telefono e Pi sulla stessa rete Tailscale (o LAN)
- VesperaHelper in ascolto sulle porte FTP sopra
- JDK 17 + Android SDK per la build

## Build

```bat
cd C:\Danger\MieiProgetti\StarStacKadam
.\gradlew.bat :app:assembleDebug
```

APK debug:

`app\build\outputs\apk\debug\app-debug.apk`

## Come usare lo stack

1. Avvia Tailscale sul telefono e sul Pi
2. Apri StarStacKadam → inserisci host Tailscale del Pi
3. Scegli **HD :2121** o **Vespera :2122** → **Connetti**
4. Entra nella cartella delle pose → seleziona 3+ JPEG/PNG → **Avvia stack**
5. Attendi la preview MEAN; regola Autostretch/Livelli; fondo solo se serve
6. **Salva PNG** quando lo stack è completo

## Pipeline frame

- Primo frame: decode → `fitEdge(1280)` = canvas di riferimento + stelle
- Frame successivi: decode → `resample` allo stesso WxH → stelle → allineamento → warp → `StackCombine.accumulate`
- Preview: `fromSum` → (opz. `BackgroundFit`) → `DisplayMap.autostretch` o `levels`

## Licenza / repo

https://github.com/IlSommoKadam/StarStacKadam
