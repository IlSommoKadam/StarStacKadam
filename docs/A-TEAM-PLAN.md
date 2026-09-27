# A-Team Plan — StarStacKadam v0.1

## Goal

Completare l’app Android **StarStacKadam** (`C:\Danger\MieiProgetti\StarStacKadam`, repo [IlSommoKadam/StarStacKadam](https://github.com/IlSommoKadam/StarStacKadam)) come client di stacking per telefono, collegato al Raspberry (VesperaHelper + Tailscale).

Validare rispetto alle specifiche già concordate:

1. **Funzionamento** (non look Singularity): allineamento+media come Siril lite; stretch/livelli come GIMP lite; fondo opzionale (idea GraXpert classica, non AI).
2. **Sorgenti foto**: browse FTP batch su HD (`:2121`) e su telescopio (`:2122`). Entrambe = multi-select + RETR; **niente poll live** in v0.1.
3. **Stack preview JPEG**: stelle → allineamento → **MEAN streaming** → stretch/livelli; anteprima che cresce sul batch. Median/Sigma e fondo aggressivo fuori UI di default.

## Stato attuale

Presente (engine, package `com.starstackadam`):

- `ImagePlane`, `Stats`, `StarFinder`, `FrameAlign`, `StackCombine`, `BackgroundFit`, `DisplayMap`
- Gradle Android, manifest con `MainActivity` + `StackWorkActivity` dichiarate ma **mancanti**
- Nessun client FTP, nessun decode JPEG/PNG/FITS, nessuna UI operativa → **non builda/non usa**

## Decisioni v0.1 (post Advisor)

1. **Solo MEAN streaming**: `StackCombine.accumulate` / `fromSum`. Median/Sigma restano nel codice ma **non** esposti in UI (RAM).
2. **Policy JPEG**: i file da Helper sono di solito JPEG già tone-mapped. Pipeline = *preview stack* (allinea + media + stretch leggero). `BackgroundFit` **off di default** (toggle opzionale). Etichetta UI: “Stack preview (JPEG)”.
3. **Batch esplicito**: multi-select su cartella FTP, poi run. Non watch/poll live su `:2122` in v0.1. La preview “cresce” durante il batch.
4. Lavoro su thread dedicato (Executor), non bloccante sull’Activity; progress via Handler.

## Scope v0.1 (implementare)

### A. Ingresso dati

| File | Ruolo |
|------|--------|
| `HostSettingsStore.java` | Host Tailscale/LAN, porte HD=2121 / Vespera=2122, ultima cartella |
| `FtpBrowser.java` | LIST/CWD/RETR anonymous PASV via Commons Net; PASV host = control host se 0.0.0.0/privato |
| `FrameDecoder.java` | Decode JPEG/PNG/WebP → `ImagePlane` (float RGB 0–1); FITS: messaggio “non supportato” |
| `FrameCache.java` | Cache locale sotto `getCacheDir()/frames` |

### B. Orchestrazione stack

| File | Ruolo |
|------|--------|
| `StackSession.java` | Frame 0: decode → fitEdge(1280) = canvas ref + ref stars. Altri: decode → resample **stesso WxH del ref** → stars → align → warp → accumulate MEAN. Cancel flag. Preview autostretch dopo ogni frame; BackgroundFit solo se toggle |
| `StackProgress.java` | Frame N/M, votes, unaligned count, note, cancellabile |

### C. UI (funzionale, scura, semplice — non clone Singularity)

| File | Ruolo |
|------|--------|
| `MainActivity.java` | Host, toggle sorgente HD/Vespera, Connetti, browser, multi-select JPEG/PNG, Avvia stack |
| `StackWorkActivity.java` | Preview, progress, Autostretch vs Levels, toggle fondo opzionale, Salva PNG |
| UI programmatica (stile Helper) |

### D. Manifest / risorse

- Activity già dichiarate; cleartext già on (FTP)
- README: Tailscale + porte Helper; batch MEAN preview

### Fuori scope v0.1

- Median/Sigma in UI, FITS, dark/flat, AI GraXpert, poll live :2122, clone UI Singularity

## Rischi

1. **PASV Tailscale**: riscrivere host dati al host di controllo se PASV annuncia IP non raggiungibile.
2. **Memoria**: solo accumulatore MEAN + un frame alla volta; `fitEdge(1280)`.
3. **Allineamento debole** su JPEG → fallback identity + contatore “non allineati”.
4. **Build**: aggiungere `MainActivity` / `StackWorkActivity` subito.

## Verifica

1. `.\gradlew.bat :app:assembleDebug` in `C:\Danger\MieiProgetti\StarStacKadam` → APK ok
2. Smoke manuale (se Pi raggiungibile): connetti host Tailscale → lista HD `:2121` → 3+ JPEG → stack MEAN con preview aggiornata → salva PNG
3. Senza rete: host errato → errore chiaro, no crash

## Ordine implementazione

1. `FrameDecoder` + `FtpBrowser` + store
2. `StackSession` wire sull’engine esistente
3. `MainActivity` + `StackWorkActivity`
4. README + assembleDebug
5. Result review A-Team
