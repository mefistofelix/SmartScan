# SmartScan

Scanner di documenti per Android in stile «Genius Scan», pensato per l'uso a una mano e senza tastiera.

[![Android CI](../../actions/workflows/android.yml/badge.svg)](../../actions/workflows/android.yml)

L'APK si scarica dalla pagina **Releases** (build di debug firmata con la chiave di debug della CI).

## Funzioni

| Area | Cosa fa |
|---|---|
| **Acquisizione** | CameraX a pieno schermo, rilevamento bordi in tempo reale (OpenCV), **scatto automatico** quando il foglio è fermo e a fuoco, **attesa automatica della pagina successiva** (rileva il cambio di scena), flash, tap-to-focus, **tasti volume come otturatore**. |
| **Flusso continuo** | L'app si apre direttamente in fotocamera (disattivabile nelle impostazioni). L'anteprima mostra tutto il fotogramma 4:3 che viene scattato, in alto sotto le icone (a schermo pieno i telefoni lunghi ne nasconderebbero circa il 40% in larghezza). Nessuna modalità da scegliere: scatti le pagine, in basso a sinistra vedi l'anteprima con il numero di pagine (tocco = elenco con eliminazione), in basso a destra scegli **PDF / IMG** (predefinito PDF) e premi **Fine**. Il documento va in archivio, OCR e upload partono in background e la fotocamera resta pronta per il documento successivo. Chiudendo la fotocamera le pagine in sospeso vengono salvate. |
| **Comandi rapidi** | Icone in alto nella fotocamera, un tocco ciascuno (la scelta resta per le volte successive, un avviso spiega cosa è cambiato): **rilevamento** a tre stati in un solo pulsante (Auto: bordi e scatto automatico → Bordi: bordi e raddrizzamento, scatti tu → Spento: pagina intera così com'è), **HQ** (qualità massima: risoluzione piena del sensore, compressione minima, nessun ridimensionamento in esportazione), **GPS** (salva la posizione con ogni pagina), **luce**. Tieni premuta un'icona per vederne il nome. La **luce** è una torcia: se attiva resta sempre accesa, non lampeggia allo scatto. L'icona elenco in alto a sinistra salva e apre l'elenco dei documenti. |
| **Note vocali** | Pulsante nota in basso a sinistra: detti una nota per il documento in corso, anche più volte. Ogni nota si aggiunge alle note del documento e a quelle dell'ultima pagina scattata. Finché il documento non è salvato, il numero sul pulsante (o la pressione lunga) mostra le note, ognuna eliminabile; l'elenco si chiude da solo dopo qualche secondo. Se il documento non ha ancora un titolo, le prime parole della prima nota diventano il nome (modificabile: tocca il titolo nella scheda del documento). Senza note il nome è automatico («Scansione data ora»). |
| **QR e codici a barre** | Durante l'OCR ogni pagina viene letta anche per QR code e codici a barre (tutti i formati, anche più di uno per pagina). I valori decodificati finiscono nei metadati. |
| **Metadati nei file** | PDF: titolo, note (Subject), categorie e tag (Keywords), codici e posizione nel dizionario Info, e su ogni pagina una nota (annotazione non stampata) con nota vocale, codici e posizione della pagina. Immagini: EXIF (descrizione, commento, GPS) e XMP con il testo completo (titolo, note, tag, nota e codici della pagina). |
| **Elaborazione** | Correzione prospettica, **raddrizzamento automatico** (deskew sulle righe di testo), editor manuale dei 4 angoli con lente d'ingrandimento, **espansione / restringimento dei bordi**, «pagina intera», rotazione, riordino ed eliminazione pagine. |
| **Filtri** | Originale, Migliorato (rimozione ombre e normalizzazione dell'illuminazione), Grigi, Bianco/nero (soglia adattiva, salvato a 1 bit). |
| **OCR** | ML Kit on-device (offline), in background con WorkManager. Testo ricercabile nell'archivio. |
| **Esportazione** | PDF, **PDF ricercabile** (livello di testo OCR invisibile), JPEG/PNG, TXT. Scrittore PDF proprio: i JPEG vengono inseriti senza ricodifica e il B/N come immagine a 1 bit compressa Flate, quindi file piccoli. Qualità Alta/Media/Compatta e pagina Auto/A4/Letter. Condivisione o «Salva come…». |
| **Archivio** | Locale (Room + file privati dell'app), ricerca su titolo, note, testo OCR e tag; filtro per ramo dell'albero delle categorie. Tieni premuto un documento per la **selezione multipla**: condividi tutti i selezionati in **un unico ZIP** oppure **eliminali** (ogni documento nel suo formato: PDF ricercabile o cartella di immagini). |
| **Categorie e tag** | Due alberi di profondità arbitraria (categorie, tag/etichette), assegnazione multipla per documento. Alberi locali modificabili e **alberi remoti** scaricati dagli endpoint (sola lettura, unione con id stabili). |
| **Endpoint di storage** | Aggiunta da **QR code** (o link `smartscan://add?cfg=…`) con conferma esplicita dell'host. HTTP multipart o WebDAV. **Upload in background** con nuovi tentativi, vincolo «solo Wi-Fi», invio automatico o manuale. |
| **Voce** | Solo dettatura, niente comandi vocali. Dettatura dei metadati **prima** (all'apertura della fotocamera), **durante** o **dopo** (a «Fine», prima del salvataggio). Dopo «Fine» la fotocamera è subito pronta, senza schermate in mezzo. |

### Metadati a voce

Parole chiave: `titolo`, `categoria`, `tag`/`etichetta`, `nota`, `data`. Esempio:

> «titolo fattura Enel categoria bollette tag urgente e casa nota pagare entro venerdì data 12 marzo 2025»

Le categorie e i tag vengono abbinati in modo approssimato (accenti, piccoli errori di riconoscimento) agli alberi locali e remoti. I tag sconosciuti possono essere creati al volo. Senza parole chiave, i nomi di tag/categorie riconosciuti vengono assegnati e il resto della frase diventa il titolo.

## Protocollo degli endpoint

### QR di configurazione

Il QR contiene questo JSON (oppure `smartscan://add?cfg=<JSON in base64url>`):

```json
{
  "v": 1,
  "name": "Archivio ufficio",
  "type": "http",
  "url": "https://scan.example.com/api/upload",
  "headers": { "Authorization": "Bearer <token>" },
  "taxonomy": "https://scan.example.com/api/taxonomy",
  "auto": true,
  "format": "pdf_ocr"
}
```

- `type`: `http` (POST multipart) oppure `webdav` (PUT nella cartella `url`).
- `format`: `pdf`, `pdf_ocr`, `images`. Vale come predefinito dell'endpoint: la scelta PDF/IMG fatta in fotocamera per ogni documento ha la precedenza. Per i documenti PDF, `pdf` invia il PDF senza testo e gli altri valori il PDF ricercabile.
- `headers`: inviati con ogni richiesta (upload e tassonomia).

Il QR contiene un token: trattalo come una password.

### Upload HTTP

`POST url` con `multipart/form-data`:

- `metadata`: JSON (vedi sotto)
- `file`: uno o più file (PDF o immagini)

Una risposta `2xx` conta come riuscita. Se il corpo è `{"id": "..."}`, l'id viene salvato come riferimento remoto. Su errori di rete, 408, 429 e 5xx l'app ritenta con backoff esponenziale. Gli altri 4xx sono errori definitivi. Ogni salvataggio di un documento già inviato lo reinvia: il server deve aggiornare il documento esistente usando `deviceDocId`.

```json
{
  "schema": "smartscan/1",
  "deviceDocId": 42,
  "title": "Fattura Enel",
  "notes": "pagare entro venerdì",
  "date": "2025-03-12",
  "createdAt": "2025-03-14T09:12:00Z",
  "updatedAt": "2025-03-14T09:13:10Z",
  "pages": 2,
  "format": "pdf_ocr",
  "categories": [{ "name": "Bollette", "path": "Casa/Bollette", "id": "c-12", "source": "endpoint" }],
  "tags": [{ "name": "urgente", "path": "urgente", "source": "local" }],
  "files": ["Fattura Enel_42.pdf"],
  "ocrText": "…",
  "pageInfo": [
    { "page": 1, "note": "pagare entro venerdì", "lat": 45.4642, "lon": 9.19,
      "codes": [{ "format": "qr", "value": "https://example.com/f/123" }] },
    { "page": 2, "codes": [] }
  ]
}
```

`id` è presente solo per le voci che provengono dalla tassonomia di **questo** endpoint. `pageInfo` (dalla 0.3) ha una voce per pagina: `note`, `lat`/`lon` (solo se il GPS era attivo) e `codes` (QR e codici a barre decodificati; `format` è `qr`, `ean13`, `code128`, `datamatrix`, `pdf417`, …). I server più vecchi possono ignorarlo.

### WebDAV

`PUT {url}/{file}` per ogni file e `PUT {url}/{nome}.json` con gli stessi metadati.

### Tassonomia remota

`GET taxonomy` → 

```json
{
  "categories": [
    { "id": "casa", "name": "Casa", "children": [ { "id": "c-12", "name": "Bollette" } ] }
  ],
  "tags": [ { "id": "t-1", "name": "urgente" } ]
}
```

Gli `id` devono restare stabili, così le assegnazioni sopravvivono alle sincronizzazioni. La sincronizzazione parte all'avvio dell'app, dopo l'aggiunta di un endpoint e manualmente.

## Build

Requisiti: JDK 17 e Android SDK 35 (oppure Android Studio, ultima versione).

```bash
gradle assembleDebug        # oppure: apri la cartella in Android Studio
```

Il repository non include il Gradle wrapper jar. Android Studio usa `gradle/wrapper/gradle-wrapper.properties`, e la CI usa Gradle 8.11.1 tramite `gradle/actions/setup-gradle`. Per creare il wrapper in locale: `gradle wrapper`.

La CI ([.github/workflows/android.yml](.github/workflows/android.yml)) compila a ogni push e PR. Un tag `v*` pubblica una release con l'APK.

## Stack

Kotlin, Jetpack Compose (Material 3), CameraX 1.4, OpenCV 4.14, ML Kit Text Recognition e Barcode Scanning, Room, WorkManager, OkHttp, kotlinx.serialization, Coil. minSdk 26, targetSdk 35.

## Stato e limiti noti

- Prima versione (0.1.x): compila in CI ma non è ancora stata provata su molti dispositivi. Le soglie di rilevamento e scatto automatico ([`AutoCaptureController`](app/src/main/java/com/smartscan/scan/AutoCaptureController.kt)) vanno tarate sul campo.
- Le stringhe dell'interfaccia sono in italiano e scritte direttamente nel codice, non ancora in `strings.xml`.
- L'APK contiene solo le librerie native `arm64-v8a` (quasi tutti i telefoni recenti). Per dispositivi 32 bit o emulatori x86 va tolto `abiFilters` da `app/build.gradle.kts`.
- La release è una build di debug. Per una build firmata serve un keystore nei secrets della CI.
- La dettatura usa il `SpeechRecognizer` di sistema. Su alcuni dispositivi emette un suono all'avvio dell'ascolto.
- Il traffico in chiaro (`http://`) è permesso per i server in LAN. L'app mostra un avviso per gli endpoint non https.
