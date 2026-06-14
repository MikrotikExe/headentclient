# TVH Client — natívny Tvheadend klient (Android + iOS)

Kotlin Multiplatform projekt: zdieľané jadro (Ktor, modely, secure storage),
natívne UI — Jetpack Compose (Android mobil + TV), SwiftUI (iOS).

Stav: Míľnik M5 — DVR. Záložka Nahrávky: dokončené nahrávky zoskupené po
kategóriách (klasifikátor prebratý z classifier.py — DVB žáner + keyword
fallback), prehrávanie cez dvrfile (libVLC), mazanie. Plánovanie nahrávky z
EPG detailu (tlačidlo Nahrať → create_by_event). M1-M4 overené na serveri.

## Čo je prebraté z Enigma2 pluginu (plugin_video_tvheadend)

Battle-tested logika portovaná do KMP jadra:
- TvhApi: retry-with-backoff (3×, 0.5/1/2s, len 5xx/408/429), stránkovanie
  start/limit do total — z tvheadend.py (FIX 0.48)
- endpoint konštanty zhodné s pluginom (stream/channel, dvrfile, imagecache,
  api/channel/grid, api/channeltag/grid, api/epg/events/grid mode=now)
- StreamUrlBuilder: live + DVR URL, credentials v URL, profile/title param
  — z _stream_urls.py
- Picon404Cache: negatívna 404 cache (1h TTL) + early-abort prah (30) proti
  OOM na slabých boxoch — z _picons.py (FIX 0.48b + FIX 0.71.1)
- ChannelRepository: 60s cache kanálov ako get_channels (ExpiringLRUCache)

Odložené na neskôr: classifier.py (1407 r., DVR kategorizácia) → M5;
HTSP protokol → post-MVP; vlastný SHA-256/512 digest (HTTPDigestAuthMulti)
→ len ak Ktor stock digest zlyhá na tvojom serveri.

## Štruktúra

```
shared/        KMP modul: TvhApi (Ktor, Basic+Digest auth), TvhServer model,
               ServerStore (EncryptedSharedPreferences / Keychain)
androidApp/    Compose UI, jeden APK pre mobil aj Android TV (leanback launcher)
iosApp/        SwiftUI, projekt sa generuje cez XcodeGen
```

## Build — Android

Požiadavky: Android Studio (Ladybug+), JDK 17.

```
cd TvhClient
./gradlew :androidApp:assembleDebug
```

Alebo otvor priečinok v Android Studiu a spusti konfiguráciu androidApp.
Pri prvom otvorení nechaj Gradle stiahnuť wrapper a závislosti — wrapper
nie je v zipe priložený, vygeneruj ho:

```
gradle wrapper --gradle-version 8.10
```

(alebo nechaj Android Studio, ponúkne to samo)

Android TV: rovnaký APK, nainštaluj cez adb install na box/emulátor,
appka je v leanback launcheri.

## Build — iOS

Požiadavky: macOS, Xcode 16+, JDK 17, XcodeGen.

```
brew install xcodegen
cd TvhClient/iosApp
xcodegen
open TvhClient.xcodeproj
```

Xcode pri builde sám zavolá gradle task embedAndSignAppleFrameworkForXcode,
ktorý skompiluje shared modul do Shared.framework.

## Test checklist M5

1. Záložka Nahrávky → zoznam dokončených nahrávok, zoskupený po kategóriách
   (Filmy, Seriály, Šport, Spravodajstvo, Šou, Detské, Hudba, Dokumenty…)
2. Kategórie sedia podľa obsahu (DVB žáner zo servera + keyword fallback)
3. Klik na nahrávku → prehrávanie cez libVLC (dvrfile), video aj zvuk
4. Pri nahrávke: kanál, dátum/čas, dĺžka v minútach
5. Ikona koša → potvrdzovací dialóg → zmazanie nahrávky (zo servera aj súbor)
6. Po zmazaní sa zoznam obnoví
7. Plánovanie: v EPG detaile relácie tlačidlo Nahrať → "Nahrávka naplánovaná"
8. Naplánovaná nahrávka sa po čase objaví v Nahrávkach (po dokončení)
9. Bez nahrávok → "Žiadne nahrávky"

Pozn.: sub-žánre (Akčný/Krimi/Sci-fi…), 1945-titulový corpus a IMDb lookup
z classifier.py zatiaľ NEsú portované — len top kategórie. Doplní sa neskôr.
DVR prehrávač zatiaľ nemá seek lištu (pridá sa v polish kroku).

## Test checklist M4

1. Dlhý klik (podržanie) na kanál → otvorí sa EPG program kanála
2. Program zoskupený po dňoch (hlavička s názvom dňa), zoradený podľa času
3. Práve bežiaci program zvýraznený (tučne + farebný čas + podsvietenie)
4. Čas začiatku vľavo (HH:MM), názov + podtitul/popis vpravo
5. Krátky klik na kanál stále spustí prehrávanie (nepletie sa s EPG)
6. Zavretie EPG (✕ vľavo hore) → návrat na zoznam kanálov
7. Kanál bez EPG → "Žiadny program"
8. Android TV: dlhé stlačenie OK na kanáli otvorí EPG

## Test checklist M3

1. Klik na kanál v zozname → otvorí sa prehrávač, video nabehne (MPEG-TS pass)
2. Ovládanie prehrávača (play/pause cez tap), návrat späť tlačidlom
3. MPEG-2 kanál (DVB-S/T2) sa prehrá — overuje HW dekód na boxe; na mobile
   ak chýba kodek → toast "Prehrávanie zlyhalo: …"
4. H.264/HEVC kanál sa prehrá
5. Zlý/nedostupný kanál → čitateľná chybová hláška, appka nespadne
6. Profil: v nastaveniach servera políčko "Stream profil" (default pass),
   zmena na iný profil (napr. ak máš HLS/matroska profil na serveri)
7. Android TV: D-pad fokus na kanáli + OK spustí prehrávač
8. Auth: prehrávanie funguje s prihlasovacími údajmi (Basic hlavička)

## Test checklist M2

1. Záložka Kanály → načíta sa zoznam (~600 kanálov), bez zamrznutia GUI
2. Picony sa zobrazujú vedľa názvov, lazy podľa scrollu (žiadny upfront burst)
3. Kanály bez piconu → placeholder s prvými 2 písmenami názvu
4. Filtre tagov hore (Všetky + jednotlivé kategórie) → klik prepne zoznam
5. Now/next: pod názvom kanálu práve bežiaci program (ak server vracia EPG)
6. Vyhľadávanie → píš názov, zoznam sa filtruje naprieč všetkými kanálmi
7. Číslo kanálu pred názvom, zoradenie podľa čísla
8. Prepnutie aktívneho servera v záložke Servery → Kanály načítajú nový server
9. Bez aktívneho servera → hláška "Žiadny aktívny server"
10. Android TV: D-pad prejde filtre aj zoznam, fokus viditeľný
11. Picon cache: druhé otvorenie appky → picony okamžite z disku (cacheDir/picons)

## Test checklist M1

1. Pridanie servera: host, port 9981, meno/heslo → Otestovať pripojenie
   → "Pripojené: Tvheadend 4.3-… (API v…)"
2. Zlé heslo → "Prihlásenie zlyhalo" (test plain aj digest režim)
3. Zlý host/port → "Pripojenie zlyhalo: …" do ~5 s
4. Uloženie, kill appky, znovuotvorenie → server v zozname, aktívny označený
5. Druhý server, prepínanie aktívneho
6. Úprava a zmazanie servera
7. Android TV: celý flow D-padom
8. Jazyk telefónu SK/CZ/EN → preklady
9. Credentials nie sú plain: adb shell, /data/data/sk.tvhclient/shared_prefs/
   tvh_secure_prefs.xml → zašifrované bloby

## Známe poznámky

- Kód nebol kompilovaný (prostredie bez Android SDK / Xcode). Očakávaj
  drobné chyby typu zlý import alebo verzia — pošli chybový výstup,
  opravím.
- usesCleartextTraffic=true a NSAllowsArbitraryLoads=true sú zámerné,
  TVH beží typicky na HTTP. HTTPS cez prepínač funguje tiež.
- Digest auth: Ktor si vyberie Basic/Digest automaticky podľa 401
  challenge zo servera — netreba nič nastavovať.

## Ďalšie míľniky

M2 kanály+picony, M3 prehrávanie (Media3 / VLCKit), M4 EPG, M5 DVR,
M6 rádio+polish. VLCKit sa pridá v M3 cez SPM: https://code.videolan.org/videolan/VLCKit
