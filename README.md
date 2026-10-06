# StarStacKadam

Client Android di stacking JPEG collegato al Raspberry che espone VesperaHelper via **Tailscale** (o LAN).

Package: `com.starstackadam` · versione **0.0.22**

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
| 0.0.10 | Autostretch, sottrazione fondo e riga di stato leggibili sulla nebulosa |
| 0.0.11 | Stack condivisibile: opzione avanzata FITS. Il live resta sui JPEG |
| 0.0.12 | Checkbox blu, leggibili sulla nebulosa |
| 0.0.13 | Aggiornamenti come ESA Meter: cartella Mega all'avvio e di nuovo dopo 24 ore |
| 0.0.14 | Allineamento a catena, stelle tipiche, pose non allineate fuori dalla media |
| 0.0.15 | Matching più tollerante sulla rotazione di campo; stelle tipiche vs nodi nebulosa |
| 0.0.16 | Raffinamento a coppie esclusive, soglia voti 6, autostretch più vicino a Vespera |
| 0.0.17 | Autostretch come livelli automatici: cielo nero, senza MTF che apre il rumore |
| 0.0.18 | Non rimedia gli `*-output.jpg` Vespera (già stackati); allinea alla media corrente |
| 0.0.19 | Anteprima Vespera = ultimo `*-output.jpg`, senza stack sul telefono |
| 0.0.20 | Data e ora dell’ultimo output Vespera in elenco e in anteprima |
| 0.0.21 | Elenco oggetti dai nomi cartella; pose, FITS e anteprima si caricano al tocco |
| 0.0.22 | Nuovo link pubblico Mega (solo cartella `pub\Pubblici`) per gli aggiornamenti |

## Cosa fa

1. Si connette in FTP anonymous (PASV) all’Helper e legge le cartelle già salvate
2. Nel tab **Oggetti** mostra la sigla registrata nella directory, le date di acquisizione e il nome comune
3. Nel tab **File FTP** resta lo scroll delle cartelle, con scelta manuale delle pose
4. **Anteprima Vespera**: scarica e mostra l’ultimo `*-output.jpg` dello strumento
5. **Stack condivisibile**: pose singole, lato 2048, esclude le pose non allineate, salva PNG + scheda JSON. Opzione avanzata **FITS** (ancora scaricati sul telefono)
6. Opzionale: sottrazione fondo polinomiale (off di default)

**Non** espone Median/Sigma in UI (restano nel codice engine: tengono tutti i frame in RAM).  
**Non** fa poll live sul telescopio.  
I **FITS** entrano solo nello stack condivisibile, con l'opzione avanzata spenta di default. L’anteprima live usa l’ultimo output JPEG di Vespera.

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
- `C:\WORK\ESA\Share\pub\Pubblici\StarStacKadam.apk`

## Come usare lo stack

1. Avvia Tailscale sul telefono e sul Pi
2. Apri StarStacKadam (versione in basso a destra). In home scegli HD o Vespera: il pallino è verde se l’FTP risponde, rosso se è offline
3. Il tab **Oggetti** elenca subito le cartelle (sigla, date, nome comune), senza leggere ogni posa. Gli endpoint si cambiano solo in **Impostazioni**
4. Filtra Galassie / Stelle / Nebulose e tocca un oggetto: solo allora carica pose, FITS e anteprima. Il tab **File FTP** serve per scorrere le directory a mano
5. **Anteprima Vespera** mostra l’ultimo `*-output.jpg` del telescopio. **Stack condivisibile** allinea le pose singole (o i FITS con l’opzione avanzata)
6. Regola Autostretch/Livelli; fondo solo se serve
7. In live: **Salva PNG**. Nel condivisibile: **Salva PNG + scheda** (`Pictures/StarStacKadam` e `Download/StarStacKadam`)

Il nome in elenco è quello della cartella (`M31`, `NGC 7000`, `2023-07-15_…_observation_M8`). Le date si leggono dal token `-a` (`M31-a20240915`) e dal prefisso delle cartelle osservazione Vaonis. Il nome comune (`Nebulosa Laguna`, `Galassia di Andromeda`, …) sta nella mappa `CommonNames.COMMON_NAME`. Un file `oggetto.json` / `object.json` / `target.json` / `meta.json` può forzare `scientificName`, `publicName` e `kind`, e si legge al tocco insieme alle pose.

## Pipeline frame

- Primo frame: decode → `fitEdge` (1280 live, 2048 condivisibile) = canvas di riferimento + stelle
- Frame successivi: decode → `resample` allo stesso WxH → stelle → allineamento → warp → `StackCombine.accumulate`
- Se i voti sono bassi o l'rms delle stelle è alto, la posa non entra nella media
- Anteprima live: solo l’ultimo `*-output.jpg` Vespera. Stack condivisibile: pose singole, allineamento sulla media corrente
- Preview: `fromSum` → (opz. `BackgroundFit`) → `DisplayMap.autostretch` o `levels`

## Licenza / repo

https://github.com/IlSommoKadam/StarStacKadam
