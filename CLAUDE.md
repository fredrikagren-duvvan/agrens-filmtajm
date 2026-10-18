# Agréns filmtajm

Android-app som visar "bäst just nu" på svenska streamingtjänster, sorterat
efter IMDb-betyg. För Fredrik och hans närmaste – personligt, icke-kommersiellt
bruk, publiceras inte i Google Play. (Från början tänkt som webbapp; Fredrik
valde 2026-10-18 en riktig Android-app som Nästa tunnelbana. Fungerar alltså
inte på iPhone.)

## Om användaren och arbetssättet

- Fredrik har aldrig programmerat. Förklara på **enkel svenska** och ge exakta
  steg för allt han själv måste göra (konton, nycklar, klick i webbgränssnitt).
- **Ett steg i taget:** vänta på Fredriks bekräftelse innan nästa steg i
  byggordningen påbörjas.
- Skriv alltid förklaringen/frågan som vanlig text – inte bara i
  sammanfattningsrutan (två gånger syntes förslag aldrig för Fredrik).
- Systerprojekt med samma upplägg (Android, inga beroenden, release-nyckel,
  självuppdatering via GitHub-releaser): `C:UsersFredrNastaTunnelbana`
  och `C:UsersFredrProjektEsterNotes` – läs deras CLAUDE.md och
  återanvänd t.ex. `Updater.kt`, `InstallReceiver` och `publicera.ps1`.

## Vad appen gör

En enda skärm med tre grupper av val:

1. **Tjänster** (kryssrutor, flera): Netflix, Disney+, HBO Max, Viaplay, Prime Video
2. **Genre** (kryssrutor, flera): Romantik, Komedi/humor, Action/thriller, Sci-fi, Drama
3. **Typ** (välj en): Filmer eller Serier

Listan uppdateras direkt när valen ändras: topp 50 titlar som ingår i
abonnemanget i Sverige just nu på **någon** av de valda tjänsterna och tillhör
**någon** av de valda genrerna. Varje rad: affisch, titel, år, IMDb-betyg,
antal röster och märken för de valda tjänster titeln finns på. Tryck på titeln
öppnar IMDb-sidan (webbläsaren eller IMDb-appen).

- **Sett-markering:** kryssruta "Sett" på varje rad och ett reglage "Dölj
  sedda" bland valen. Sedda titlar sparas per IMDb-id. Listan fylls på så att
  det fortfarande är 50 titlar när sedda är dolda. Sedda visas blekta.
- **Dölj-markering** (Fredriks önskan 2026-10-18): knapp "Dölj" på varje rad
  för titlar som är helt ointressanta – de försvinner ur listan. Reglaget
  "Visa dolda" visar dem blekta med knappen "Visa igen".
- Valen och sedda titlar sparas i telefonen (SharedPreferences). Varje telefon
  har sin egen lista – inget delas mellan familjemedlemmar.
- Svenskt gränssnitt, stöd för mörkt läge.

## Arkitektur

```
GitHub Actions (1 gång/dygn)
  └─ node scripts/uppdatera.mjs   (TMDB_API_KEY från GitHub-hemlighet)
       ├─ TMDB /watch/providers/movie|tv?watch_region=SE  → provider-id
       ├─ TMDB /discover/movie och /discover/tv           → kandidater
       │     watch_region=SE, with_watch_providers=<id>, language=sv-SE
       ├─ TMDB /movie|tv/{id}?append_to_response=external_ids → IMDb-id (cache)
       ├─ datasets.imdbws.com/title.ratings.tsv.gz         → betyg + röster
       ├─ TMDB /movie|tv/{id}/watch/providers → SE.flatrate → tjänster
       └─ skriver docs/data.json → publiceras via GitHub Pages
Android-appen (Kotlin, inga beroenden)
  └─ hämtar data.json (högst en gång per dygn, sparas lokalt), filtrerar,
     rankar och visar listan. Affischer från image.tmdb.org.
```

- **Skript: Node** (redan installerat, v24). Inga npm-beroenden – bara inbyggt
  (`fetch`, `zlib`, `fs`).
- **App:** samma upplägg som Nästa tunnelbana – Kotlin, AGP/Gradle/SDK-versioner
  som där, inga beroenden (bilder laddas med HttpURLConnection + BitmapFactory
  och en liten minnescache).
- **Provider-id hårdkodas inte** – hämtas från TMDB varje körning och matchas på
  namn (t.ex. "Max"/"HBO Max"), eftersom de ändras.
- **Bara "ingår i abonnemanget"** (flatrate), inte hyra/köp – kontrolleras per
  titel, se Status.
- **TMDB-nyckeln** ligger bara som hemlighet `TMDB_API_KEY` i GitHub och
  lokalt i `.env` (står i `.gitignore`). Den får aldrig hamna i koden, i
  `data.json` eller i appen.
- IMDb-id och engelsk titel cachas i `cache/imdb-id.json` (i Actions via
  `actions/cache`, nyckel `imdb-id-<run_id>` med restore-key `imdb-id-`).

### GitHub

- Förråd (offentligt, krävs för gratis Pages):
  https://github.com/fredrikagren-duvvan/agrens-filmtajm
- Hemlighet `TMDB_API_KEY` satt med `gh secret set` från `.env`.
- `.github/workflows/uppdatera.yml`: varje natt 03:17 UTC + manuellt
  ("Run workflow" under Actions, eller `gh workflow run uppdatera.yml`).
- **Pages publiceras från Actions** (build_type=workflow), inte från en gren:
  mappen `docs/` laddas upp som artefakt. `docs/data.json` checkas alltså
  aldrig in (står i `.gitignore`) – förrådet växer inte för varje natt.
- Data: https://fredrikagren-duvvan.github.io/agrens-filmtajm/data.json

### data.json (kompakt)

`{ uppdaterad, titlar: [{ t, y, k, g, s, i, r, v, p }] }` – titel, år, typ
(`f`/`s`), genrer (`rom kom act sci dra`), tjänster (`nf dp hbo vp pv`),
IMDb-id, betyg, röster, affischsökväg (`https://image.tmdb.org/t/p/w92` +
sökväg). Bara titlar med minst `MIN_IMDB_ROSTER` (1 000) IMDb-röster.

## Genrer

TMDB har olika genrelistor för film och tv. Mappningen mot våra fem genrer
bestäms i byggsteg 1 och **visas för Fredrik innan vi bygger vidare**. Den
bekräftade mappningen skrivs in här. Action/thriller omfattar både action och
thriller. (TV saknar ren romantik – föreslå lösning.)

Bekräftad 2026-10-18 (tabellen `GENRER` i `scripts/uppdatera.mjs`):

| Vår genre | Film | Serie |
|---|---|---|
| rom Romantik | 10749 Romance | keyword 9840 "romance" (tv saknar genren) |
| kom Komedi/humor | 35 Comedy | 35 Comedy |
| act Action/thriller | 28 Action + 53 Thriller | 10759 Action & Adventure |
| sci Sci-fi | 878 Science Fiction | 10765 Sci-Fi & Fantasy |
| dra Drama | 18 Drama | 18 Drama |

- Svensk titel används om den är i latinsk skrift, annars den engelska
  (TMDB ger originaltiteln, t.ex. hindi, när svensk översättning saknas).

## Rankning

Viktat (Bayesianskt) betyg som IMDb:s topp 250:

```
viktat = (v / (v + m)) * R + (m / (v + m)) * C
```

R = titelns IMDb-betyg, v = antal röster, m = minimiantal röster,
C = snittbetyget för alla titlar av samma typ i datat.

- Minimiantal röster: **5 000 för filmer, 10 000 för serier** (serier höjt från
  2 000 den 2026-10-18 – indiska serier med få men höga betyg hamnade överst) – titlar under
  gränsen visas inte.
- Värdena ligger som lättändrade inställningar (konstanter överst i appens
  rankningskod).
- Listan visar vanligt IMDb-betyg men sorteras på det viktade.

## Byggordning

1. Skriptet lokalt: data för **en** tjänst → JSON. Visa utdrag för Fredrik.
2. Alla fem tjänster, både film och serier.
3. Android-appen, testad i emulatorn mot den lokala JSON-filen.
4. GitHub-repo, GitHub Pages (för data.json) och GitHub Actions med daglig
   körning. Appen byter till att hämta data.json därifrån.
5. Signerad release, självuppdatering och `publicera.ps1` som i Nästa
   tunnelbana, installation på Fredriks telefon.

## Villkor och attribution

TMDB:s API och IMDb:s datafiler får bara användas personligt och
icke-kommersiellt. Längst ner i listan (eller en "Om"-ruta) måste stå:

- TMDB-loggan och texten "This product uses the TMDB API but is not endorsed or
  certified by TMDB."
- "Streamingdata från JustWatch"
- "Betyg från IMDb"

## Släppta versioner

- 1.0 (versionCode 1), 2026-10-18: första publicerade versionen, installerad
  på Fredriks telefon.
  Direktlänk: https://github.com/fredrikagren-duvvan/agrens-filmtajm-releases/releases/download/v1.0/AgrensFilmtajm-1.0.apk
  Själva uppdateringsflödet på telefonen är otestat tills 1.1 släpps.

## Självuppdatering och publicering

Samma upplägg som Nästa tunnelbana (`Updater.kt` + `InstallReceiver` är
kopierade därifrån, utan widgetdelarna).

- Appen läser `https://github.com/fredrikagren-duvvan/agrens-filmtajm-releases/releases/latest/download/version.json`
  när den visas (högst var 10:e min) och via "Sök efter uppdatering" längst
  ner. Debugbyggen letar inte efter uppdateringar.
- Installationsfilerna ligger i ett eget förråd, `agrens-filmtajm-releases`
  (skapas av `publicera.ps1` första gången), inte i kodförrådet.
- **Publicera:** höj `versionCode`/`versionName` i `app/build.gradle.kts`,
  bygg signerad release-APK i Android Studio (Build → Generate Signed App
  Bundle or APK → APK → release), kör sedan i ett vanligt PowerShell-fönster
  från projektmappen:
  `powershell -ExecutionPolicy Bypass -File .publicera.ps1 -Nyheter "…"`.
  Skriptet tar den nyaste av `appeleaseapp-release.apk` och
  `appuildoutputsapkeleaseapp-release.apk`, kontrollerar paket och
  signatur och sparar en kopia i `C:UsersFredrAgrensFilmtajm-installationsfiler`.
- Release-nyckel: samma som EsterNotes
  (`C:UsersFredrEsterNotes-nyckelesternotes-release.jks`, alias `esternotes`).
  Lösenordet har bara Fredrik – fråga aldrig efter det.
- Testläge i debugbyggen: lägg en apk i `cache/updates/test.apk` (via
  `run-as`) och starta MainActivity med action `se.minapp.filmtajm.TEST_INSTALL`.
  Testat 2026-10-18: 1.0 → 1.1-test fungerade (efter "Install anyway" i Play
  Protect – More details → Install anyway).
- Debug- och releaseversionen kan inte ligga på samma telefon samtidigt.

## Miljö

- Projektmapp: `C:\Users\Fredr\Projekt\AgrensFilmtajm`.
- Node 24, git och GitHub CLI (`gh`) finns installerade.

## Status

- 2026-10-18: CLAUDE.md skapad. TMDB-nyckeln ligger i `.env` och fungerar.
  Tjänsterna heter i TMDB (SE): "Netflix", "Disney Plus", "HBO Max", "Viaplay",
  "Amazon Prime Video" – matcha exakt namn, inte "Netflix Kids" eller
  "... Amazon Channel".
- 2026-10-18: Steg 1 klart – `node scripts/uppdatera.mjs Netflix` ger ca 6 200
  titlar (880 kB) på ca 3 min första gången, sedan snabbt tack vare
  `cache/imdb-id.json`.
- 2026-10-18: Steg 2 klart – alla fem tjänster: 10 568 titlar med minst 1 000
  IMDb-röster (`MIN_IMDB_ROSTER`), 1,5 MB (530 kB packat), ca 4 min.
- 2026-10-18: **Bara "ingår i abonnemanget".** Discovers
  `with_watch_monetization_types=flatrate` gäller inte per tjänst (Sagan om
  ringen hyrs på Viaplay men ingår på Netflix → kom med för Viaplay). Nu
  kontrolleras `/{typ}/{id}/watch/providers` → `SE.flatrate` för varje titel
  med betyg. Resultat: 9 825 titlar, ca 5 min. Viaplay-filmer 2 811 → 744.
  Aspirants m.fl. ligger kvar högt även med 10 000 röster (de har många röster).
- 2026-10-18: Fredrik valde riktig Android-app i stället för webbapp.
- 2026-10-18: Steg 3 klart – appen byggd och testad i emulatorn (val, Sett,
  Dölj, Dölj sedda, Visa dolda, Serier, mörkt läge). Filer:
  `Titles.kt` (tjänster, genrer, data, rankning – inställningarna
  `MIN_VOTES_*`, `TOP_COUNT`, `DATA_URL` ligger där), `Settings.kt`,
  `Posters.kt` (affischer, disk-cache via HttpResponseCache), `MainActivity.kt`.
  `DATA_URL` pekar på `fredrikagren-duvvan.github.io/agrens-filmtajm/data.json`
  som inte finns än (steg 4). Testa i emulatorn genom att lägga filen i appen:
  `adb push docs/data.json /data/local/tmp/` och
  `adb shell run-as se.minapp.filmtajm cp /data/local/tmp/data.json files/`
  (i Git Bash: `MSYS_NO_PATHCONV=1`). Data äldre än 6 h hämtas om.
- Debugbygget läser data.json på ca 5 s i emulatorn, ett icke-debugbart bygge
  på 0,3 s – det är debugläget, inte koden. data.json läses med `JsonReader`.
- 2026-10-18: Steg 4 klart – förråd, hemlighet, Pages och Actions. Första
  körningen (tom cache, 14 180 IMDb-uppslag) tog ca 10 min och gav 9 825
  titlar. Appen i emulatorn hämtade data.json från Pages själv.
- 2026-10-18: Steg 5 – självuppdatering och `publicera.ps1` klara och testade
  i emulatorn. 1.0 publicerad och installerad – alla fem byggstegen klara.
