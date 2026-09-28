# StarStacKadam

Client Android di stacking JPEG collegato al Raspberry che espone VesperaHelper via **Tailscale** (o LAN).

Package: `com.starstackadam` · versione **0.0.9**

`versionName` parte da **0.0.1**. A ogni modifica si aumenta l’ultimo numero (`0.0.2`, `0.0.3`, …) e di 1 il `versionCode`.

| Versione | Contenuto |
|----------|-----------|
| 0.0.1 | Stack preview JPEG (MEAN, batch FTP, lato 1280) |
| 0.0.2 | Home con oggetti salvati (nome scientifico e pubblico). Due stack: visione live e file condivisibile |
| 0.0.3 | La versione sale a ogni modifica, non solo a quelle grosse |
| 0.0.4 | Tab Oggetti (sigla, date, nome comune) e tab File FTP con lo scroll delle cartelle |
| 0.0.5 | APK in Share senza «debug». Controllo versione sul link pubblico all’avvio e da Impostazioni |
| 0.0.6 | Endpoint solo in Impostazioni (HD e Vespera separati). Pallino online/offline. FTP del Pi sul canale dati giusto |
| 0.0.7 | Icona senza il nome disegnato, così il cerchio del telefono non taglia la scritta |
| 0.0.8 | Versione in home più chiara e più grande, leggibile sullo sfondo |
| 0.0.9 | Lo stack continua in secondo piano, con notifica, anche a schermo spento |

## Cosa fa

1. Si connette in FTP anonymous (PASV) all’Helper e legge le cartelle già salvate
2. Nel tab **Oggetti** mostra la sigla registrata nella directory, le date di acquisizione e il nome comune
3. Nel tab **File FTP** resta lo scroll delle cartelle, con scelta manuale delle pose
4. **Visione live**: media streaming, lato 1280, anteprima che cresce
5. **Stack condivisibile**: lato 2048, esclude le pose non allineate (meno di 4 stelle in comune), salva PNG senza perdita e una scheda JSON
6. Opzionale: sottrazione fondo polinomiale (off di default)

**Non** espone Median/Sigma in UI (restano nel codice engine: tengono tutti i frame in RAM).  
**Non** fa poll live sul telescopio.  
**Non** supporta FITS.

## Porte FTP (VesperaHelper)

| Sorgente | Porta | Contenuto tipico |
|----------|------:|------------------|
| HD Helper | **2121** | Foto già sul disco del Pi / sync |
| Vespera   | **2122** | Storage telescopio (sempre multi-select batch) |

Default endpoint (solo in **Impostazioni**):

| Sorgente | Default |
|----------|---------|
| HD | `raspe:2121` |
| Vespera | `raspe:2122` |

`raspe` è l’hostname MagicDNS Tailscale del Pi (o sostituiscilo con l’IP `100.x.y.z` / LAN). In Impostazioni ogni endpoint accetta `host:porta` o `ftp://host:porta`.

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

APK (anche copiato di default in Share, insieme a `starstackadam-version.json`):

- `app\build\outputs\apk\debug\app-debug.apk`
- `C:\WORK\ESA\Share\StarStacKadam.apk`

## Come usare lo stack

1. Avvia Tailscale sul telefono e sul Pi
2. Apri StarStacKadam (versione in basso a destra). In home scegli HD o Vespera: il pallino è verde se l’FTP risponde, rosso se è offline
3. Il tab **Oggetti** si carica da solo sulla sorgente scelta (HD di default): sigla della cartella, date e nome comune. Gli endpoint si cambiano solo in **Impostazioni**
4. Filtra Galassie / Stelle / Nebulose, tocca un oggetto. Il tab **File FTP** serve solo per scorrere le directory a mano
5. **Visione live** per guardare lo stack mentre cresce, oppure **Stack condivisibile** per il file da passare ad altri
6. Regola Autostretch/Livelli; fondo solo se serve
7. In live: **Salva PNG**. Nel condivisibile: **Salva PNG + scheda** (`Pictures/StarStacKadam` e `Download/StarStacKadam`)

Il nome in elenco è quello della cartella (`M31`, `NGC 7000`, `2023-07-15_…_observation_M8`). Le date si leggono dal token `-a` (`M31-a20240915`) e dal prefisso delle cartelle osservazione Vaonis. Il nome comune (`Nebulosa Laguna`, `Galassia di Andromeda`, …) sta nella mappa `CommonNames.COMMON_NAME`. Un file `oggetto.json` / `object.json` / `target.json` / `meta.json` può forzare `scientificName`, `publicName` e `kind`.

## Pipeline frame

- Primo frame: decode → `fitEdge` (1280 live, 2048 condivisibile) = canvas di riferimento + stelle
- Frame successivi: decode → `resample` allo stesso WxH → stelle → allineamento → warp → `StackCombine.accumulate`
- Condivisibile: se i voti di allineamento sono sotto 4, la posa non entra nella media
- Preview: `fromSum` → (opz. `BackgroundFit`) → `DisplayMap.autostretch` o `levels`

## Licenza / repo

https://github.com/IlSommoKadam/StarStacKadam
