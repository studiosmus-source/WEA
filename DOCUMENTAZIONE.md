# WEA — Widget meteo con effetto vetro dell'800

**Android AppWidget nativo · Java · Canvas/Bitmap · OpenWeatherMap API**

---

## Indice

1. [Concetto e obiettivo](#1-concetto-e-obiettivo)
2. [Architettura generale](#2-architettura-generale)
3. [Pipeline di rendering](#3-pipeline-di-rendering)
4. [Generazione sfondo — Cielo e mare](#4-generazione-sfondo--cielo-e-mare)
5. [Effetti nella scena (dietro il vetro)](#5-effetti-nella-scena-dietro-il-vetro)
6. [Effetto vetro dell'800](#6-effetto-vetro-dell800)
7. [Effetti sul vetro (pioggia, tinta)](#7-effetti-sul-vetro-pioggia-tinta)
8. [Ora e data](#8-ora-e-data)
9. [Cornice in legno](#9-cornice-in-legno)
10. [Sistema di animazione — RenderService](#10-sistema-di-animazione--renderservice)
11. [Meteo — WeatherManager e WeatherUpdateService](#11-meteo--weathermanager-e-weatherupdateservice)
12. [Configurazione e test effetti](#12-configurazione-e-test-effetti)
13. [File sorgente](#13-file-sorgente)

---

## 1. Concetto e obiettivo

WEA è un **widget per la home screen Android** che simula la vista attraverso un vetro imperfetto dell'Ottocento su un paesaggio marino mediterraneo. Ora e data sono sempre leggibili e nitide; lo sfondo e gli effetti cambiano in tempo reale in base all'ora del giorno e alle condizioni meteo.

| Caratteristica | Dettaglio |
|---|---|
| Tipo | AppWidgetProvider Android (home screen widget) |
| Rendering | Bitmap generata via Canvas Java, aggiornata via RemoteViews |
| Aggiornamento | Ogni 2 secondi (RenderService foreground) |
| Meteo | OpenWeatherMap API 2.5, posizione GPS o città |
| Dimensioni | 2 righe fisse, larghezza ridimensionabile (`resizeMode=horizontal`) |
| API minima | Android 5.0 (API 21) |

---

## 2. Architettura generale

| Componente | Tipo Android | Responsabilità |
|---|---|---|
| `SeaWidget` | AppWidgetProvider | Coordinatore: riceve tick, aggiorna widget, gestisce AlarmManager |
| `WidgetRenderer` | Classe statica | Genera l'intera Bitmap (sfondo + effetti + vetro + testo + cornice) |
| `RenderService` | Foreground Service | Tick ogni 2 s → chiama `updateAllWidgets()` per animazione fluida |
| `WeatherUpdateService` | IntentService | Fetch HTTP OpenWeatherMap, salva condizione in SharedPreferences |
| `WeatherManager` | Classe statica | Legge/scrive condizione meteo; gestisce override di test |
| `ConfigActivity` | Activity | Imposta città/GPS/API key; pannello test effetti |
| `BootReceiver` | BroadcastReceiver | Riavvia RenderService e AlarmManager dopo il reboot |

> Il widget non usa Gradle, AGP, o librerie esterne. La build è uno script bash che chiama direttamente `aapt`, `javac`, `dx`, `zipalign` e `apksigner`.

---

## 3. Pipeline di rendering

Ogni 2 secondi `WidgetRenderer.render()` produce una nuova Bitmap che viene passata a `RemoteViews.setImageViewBitmap()`. La pipeline è strettamente sequenziale:

```
render(ctx, dpWidth, dpHeight)
 │
 ├─ generateSeaSky()        → Bitmap base: cielo + glow + costa + foschia + mare
 │
 ├─ addSceneEffects()       → disegna SULLA bitmap base (prima della distorsione)
 │    ├─ drawRainInScene()   → pioggia nella scena, 2 layer
 │    ├─ drawRainRipples()   → cerchi d'impatto sull'acqua
 │    ├─ drawWaterSparkles() → riflessi del sole (CLEAR, giorno)
 │    ├─ drawBirds()         → gabbiani in volo (CLEAR, giorno)
 │    ├─ drawLeaves()        → foglie portate dal vento
 │    └─ drawStars()         → stelle con brillucciatura (notte/dusk)
 │
 ├─ applyGlassDistortion()  → nuova Bitmap: distorsione pixel-per-pixel
 │
 ├─ drawGlassSurface()      → tinta minerale, vignette, bolle d'aria, striature
 │
 ├─ drawRainOnGlass()       → pioggia sul vetro (3 layer + wet tint) [RAINY/STORMY]
 │
 ├─ drawTimeDate()          → ora e data SEMPRE nitidi (sopra tutto)
 │
 └─ drawWindowFrame()       → cornice in legno invecchiato
```

---

## 4. Generazione sfondo — Cielo e mare

### 4.1 Ore del giorno

| Fascia | Ore | Palette cielo |
|---|---|---|
| DAWN | 5–7 | Viola scuro → rosso-bordeaux → arancio caldo |
| MORNING | 7–11 | Blu notte → blu vivo → celeste chiaro |
| NOON | 11–14 | Blu cobalto → azzurro → celeste brillante |
| AFTERNOON | 14–17 | Blu medio → oro caldo all'orizzonte |
| SUNSET | 17–20 | Viola → rosso bruciato → arancio intenso |
| DUSK | 20–22 | Quasi nero → blu notte profondo |
| NIGHT | 22–5 | Nero assoluto → blu notte scurissimo |

### 4.2 Cielo — `drawSky()`

Tre stop di gradiente lineare verticale (top → mid → orizzonte). Il meteo **CLOUDY/WINDY** desatura tutti i colori del 45% verso grigio; **RAINY/STORMY** li scurisce del 65–70% verso grigio-blu scuro.

### 4.3 Glow sole/luna — `drawSunMoonGlow()`

Un `RadialGradient` molto esteso simula il bagliore diffuso **senza mai disegnare un disco**. La posizione del sole cambia per fascia oraria (basso a sinistra all'alba, alto al centro a mezzogiorno, basso a destra al tramonto). Di notte/crepuscolo appare un glow lunare argentato in alto a destra. Con STORMY il glow è soppresso.

### 4.4 Silhouette della costa — `drawCoastSilhouette()`

Una path con due curve di Bézier cubiche disegna profili collinari lontani appena sopra la linea dell'orizzonte (`horizonY = h * 0.52`). Il colore è semi-trasparente e varia con l'ora; in caso di pioggia/temporale la visibilità è ridotta al 40%.

### 4.5 Foschia orizzonte — `drawHorizonHaze()`

Un gradiente lineare simmetrico (Mirror) attorno alla linea di orizzonte simula la diffusione atmosferica. Il colore cambia con l'ora: arancio caldo al tramonto, blu-bianco a mezzogiorno, viola a notte.

### 4.6 Mare — `drawSea()`

Gradiente verticale dal colore di superficie al colore abissale. Sopra di esso:
- **`drawWaveLines()`**: 14 linee ondulate con prospettiva (scalate in larghezza e opacità in base alla distanza dall'orizzonte).
- **`drawSeaReflection()`**: colonna trapezoidale semi-trasparente che simula il riflesso del sole/luna sull'acqua.

---

## 5. Effetti nella scena (dietro il vetro)

Questi effetti sono disegnati sulla bitmap base *prima* della distorsione del vetro: appaiono quindi leggermente deformati dal vetro stesso, aumentando la coerenza visiva.

### 5.1 Pioggia nella scena — `drawRainInScene()`

| Layer | Posizione | Caratteristiche |
|---|---|---|
| Lontano (cielo) | Sopra l'orizzonte | Alpha 10, spessore 0.4, corte, quasi verticali |
| Vicino (mare) | Sotto l'orizzonte | Alpha 16, spessore 0.55, scalate in prospettiva, angolate dal vento |

L'angolo varia: **7°** per RAINY, **18°** per STORMY. Il seed temporale cambia ogni 150 ms.

### 5.2 Cerchi d'impatto — `drawRainRipples()`

Ellissi che si espandono nel tempo (`now % 600ms`) con alpha inversamente proporzionale al raggio. La prospettiva è simulata: cerchi vicini all'orizzonte più piccoli ed ellittici; quelli in primo piano più grandi e quasi circolari.

### 5.3 Riflessi del sole — `drawWaterSparkles()`

14–26 riflessi a forma di croce (+×) sul mare. Ogni riflesso ha un proprio periodo di pulsazione (2.5–7.5 s) e una fase casuale → lampeggiano in modo indipendente. Solo con **CLEAR** di giorno.

### 5.4 Gabbiani in volo — `drawBirds()`

7 gabbiani con parametri fissi (hardcoded) per animazione deterministica:

```java
// {cicloMs, frazioneY_rispetto_orizzonte, sfasamento_0..1, scalaDimensione}
{28000f, 0.65f, 0.00f, 1.00f},  // ciclo 28 s
{35000f, 0.73f, 0.35f, 1.35f},  // ciclo 35 s
{22000f, 0.58f, 0.62f, 0.80f},
{40000f, 0.81f, 0.15f, 1.65f},
{31000f, 0.68f, 0.80f, 1.10f},
{25000f, 0.76f, 0.50f, 1.45f},
{18000f, 0.60f, 0.28f, 0.70f},
```

La posizione X è `(now + phaseOffset) % cycle / cycle` → scorre da sinistra a destra e ricomincia. Le ali battono in sincronia (`|sin(now % 1600ms * PI)|`).

### 5.5 Foglie — `drawLeaves()`

| Condizione | Numero foglie | Velocità px/s |
|---|---|---|
| STORMY | 12–20 | 95 |
| WINDY | 7–12 | 58 |
| RAINY | 3–7 | 30 |
| CLEAR | 1–4 | 16 |

Ogni foglia ha posizione di partenza, velocità (x,y) e rotazione individuali. Le foglie sono **scalate in base alla posizione verticale**: più grandi in basso (vicino allo spettatore), più piccole in alto (lontane).

### 5.6 Stelle — `drawStars()`

30–50 stelle in posizioni fisse (seed orario). Il 28% "brilluccica" con periodo individuale (2–5.5 s):
```
bright = base * (0.3 + 0.7 * |sin(now/period * PI + phase)|)
```
Il 12% sono stelle "grandi" con un bagliore a croce.

---

## 6. Effetto vetro dell'800

### 6.1 Distorsione delle onde — `applyGlassDistortion()`

Operazione pixel-per-pixel. Lo spostamento di campionamento è calcolato da una somma di onde sinusoidali:

```java
double dx = 3.2 * sin(y*0.014+0.53) + 1.8 * cos(x*0.011+y*0.007);
double dy = 2.8 * cos(x*0.013+1.21) + 1.6 * sin(y*0.010+x*0.006);
// Micro-turbolenza ad alta frequenza:
double dx2 = 0.9 * sin(x*0.048+y*0.031);
double dy2 = 0.7 * cos(y*0.042+x*0.022);
```

Usa `getPixels()`/`setPixels()` in blocco. Per un widget 360×220 px: ~80.000 pixel, ~320.000 operazioni trigonometriche — eseguito in <50 ms su hardware moderno.

### 6.2 Superficie del vetro — `drawGlassSurface()`

1. **Tinta minerale**: rettangolo verde-grigio semi-trasparente su tutta la superficie.
2. **Vignette**: `RadialGradient` dal centro verso i bordi, nero alpha 70.
3. **Riflesso superiore**: gradiente bianco→trasparente nel 10% superiore.
4. **Bolle d'aria** (10 posizioni fisse): cerchi con bordo verde-grigio, riflesso interno bianco.
5. **Striature di colata** (3 path): linee cubiche verticali sottilissime, bianco alpha 10–18.

---

## 7. Effetti sul vetro (pioggia, tinta)

Disegnati *dopo* la distorsione: appaiono nitidi, come depositati fisicamente sul vetro.

### 7.1 Pioggia sul vetro — `drawRainOnGlass()`

| Layer | Elementi | Effetto |
|---|---|---|
| Tinta wet | Rettangolo pieno | Overlay blu-grigio `argb(20, 85, 120, 160)` su tutto |
| Lontano | 40 tratti | Alpha 28, spessore 0.5 px, brevi |
| Medio | 22 tratti | Alpha 44, spessore 0.9 px, più lunghi |
| Vicino | 14 gocce | Lacrime con highlight bianco + streak che cola sotto |

Angolo fisso a 7°. Il seed cambia con `now` direttamente → la pioggia si muove ogni 2 s.

---

## 8. Ora e data

Disegnati per ultimi, sempre in primo piano, **mai** distortie dal vetro:
- **Ora**: font sans-serif bold, dimensione 30% dell'altezza del widget, ombra morbida, centrata.
- **Data**: font normal, 13% dell'altezza, spaziatura lettere larga, centrata sotto l'ora.
- Formato italiano: `Lun  5 Gen`

---

## 9. Cornice in legno — `drawWindowFrame()`

Quattro barre di spessore `t = min(w,h) * 0.085` con gradiente che simula legno invecchiato. Dettagli:
- **Grana del legno**: linee sottili ogni 5.5 px, bianco alpha 22.
- **Effetto 3D**: bordo interno superiore/sinistro chiaro, inferiore/destro scuro.
- **Ombra proiettata**: gradiente scuro verso l'interno del vetro.
- **Blocchi agli angoli**: quadrati più scuri.
- **Chiodini**: 4 cerchi dorati semi-trasparenti.

---

## 10. Sistema di animazione — RenderService

### Perché un Service

Un AppWidget è una Bitmap statica: senza un processo attivo che la rigeneri periodicamente non ci può essere animazione. `AlarmManager` ha un minimo di ~1 minuto; un **foreground Service** con `Handler` interno permette tick ogni 2 secondi.

### Funzionamento

```java
Runnable tick = () -> {
    SeaWidget.updateAllWidgets(context);  // → WidgetRenderer.render()
    handler.postDelayed(this, 2000L);     // riprogramma se stesso
};
```

Il service usa `START_STICKY`: Android lo riavvia se viene terminato. Il tick del meteo (ogni minuto) chiama anche `RenderService.start()` come fallback — se il servizio è stato killato dalla battery optimization, riparte entro 60 s.

### Seed temporali

| Variabile | Formula | Cambia ogni | Usata per |
|---|---|---|---|
| `now` | `currentTimeMillis()` | Ogni render (2 s) | Posizione uccelli, pioggia, stelle, foglie |
| `timeSeed` | `now / 60000` | 1 minuto | Sfondo (onde, fase glow) |
| `hourSeed` | `now / 3600000` | 1 ora | Posizioni fisse sparkles/stelle/foglie |

---

## 11. Meteo — WeatherManager e WeatherUpdateService

### Condizioni meteo

| Enum | Codici OWM | Effetti abilitati |
|---|---|---|
| **CLEAR** | 800 | Sole, uccelli, sparkles, foglie (poche) |
| **CLOUDY** | 801–804, 600–799 | Cielo desaturato, foglie medie |
| **RAINY** | 300–599 | Pioggia 3-layer, ripples, vetro bagnato, foglie |
| **STORMY** | 200–299 | Come RAINY, pioggia intensa, cielo scuro, no glow |
| **WINDY** | CLEAR/CLOUDY + vento >8 m/s | Cielo parziale, foglie abbondanti |

### Flusso di aggiornamento

```
AlarmManager (ogni 60 s)
  → SeaWidget.onReceive(ACTION_WEATHER_TICK)
    → WeatherUpdateService.onHandleIntent()
      → HTTP GET api.openweathermap.org/data/2.5/weather
      → parse JSON (weather.id, wind.speed)
      → WeatherManager.saveCondition() → SharedPreferences
    → SeaWidget.updateAllWidgets()
```

---

## 12. Configurazione e test effetti

La `ConfigActivity` si apre toccando il widget. Permette di:
- Inserire manualmente la città (fetch meteo per nome)
- Rilevare e salvare la posizione GPS
- Modificare la chiave API OpenWeatherMap
- **Forzare istantaneamente una condizione meteo** per testare gli effetti

I pulsanti di test (Sereno / Nuvoloso / Ventoso / Pioggia / Temporale / Meteo reale) aggiornano l'override in SharedPreferences e chiamano immediatamente `updateAllWidgets()`: il widget cambia aspetto in meno di 2 secondi.

---

## 13. File sorgente

| File | Righe | Funzione |
|---|---|---|
| `WidgetRenderer.java` | ~850 | Intera pipeline grafica |
| `SeaWidget.java` | ~120 | AppWidgetProvider, scheduling |
| `RenderService.java` | ~100 | Foreground service, tick 2 s |
| `WeatherUpdateService.java` | ~90 | Fetch HTTP meteo |
| `WeatherManager.java` | ~60 | Gestione condizione + override test |
| `ConfigActivity.java` | ~140 | UI configurazione e test |
| `BootReceiver.java` | ~15 | Restart su boot |
| `build.sh` | ~80 | Build manuale (aapt→javac→dx→zip→sign) |
| `AndroidManifest.xml` | ~50 | Dichiarazione componenti e permessi |
| `activity_config.xml` | ~190 | Layout configurazione |

> **Dipendenze esterne: nessuna.** Tutto il rendering usa unicamente le API del framework Android standard (`android.graphics.Canvas`, `Bitmap`, `Paint`, `Path`, `LinearGradient`, `RadialGradient`). Nessun engine grafico, libreria di animazione o framework di terze parti.
