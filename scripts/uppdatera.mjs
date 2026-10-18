// Hämtar filmer och serier på svenska streamingtjänster från TMDB, matchar
// IMDb-betyg och skriver docs/data.json.
//
// Kör: node scripts/uppdatera.mjs              (alla tjänster)
//      node scripts/uppdatera.mjs Netflix      (bara en tjänst, för test)
// Nyckeln läses från miljövariabeln TMDB_API_KEY eller från filen .env.

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const ROT = path.resolve(import.meta.dirname, '..');
const UTFIL = path.join(ROT, 'docs', 'data.json');
const CACHEFIL = path.join(ROT, 'cache', 'imdb-id.json');

// Våra tjänster: kortnamn → namn som TMDB använder (flera om namnet ändrats).
const TJANSTER = {
  nf: ['Netflix'],
  dp: ['Disney Plus', 'Disney+'],
  hbo: ['HBO Max', 'Max'],
  vp: ['Viaplay'],
  pv: ['Amazon Prime Video', 'Prime Video'],
};

// Våra genrer → TMDB:s genre-id. Serier saknar romantik, där används
// ämnesordet "romance" (keyword 9840) i stället.
const GENRER = {
  movie: { rom: [10749], kom: [35], act: [28, 53], sci: [878], dra: [18] },
  tv: { kom: [35], act: [10759], sci: [10765], dra: [18] },
};
const TV_ROMANTIK_KEYWORD = 9840;

// Titlar med färre TMDB-röster än så hoppas över (sparar anrop). TMDB har
// långt färre röster än IMDb, så gränsen är låg med flit.
const MIN_TMDB_ROSTER = 5;

// Titlar med färre IMDb-röster än så skrivs inte till data.json (håller filen
// liten). Måste vara lägre än minimiantalet röster i docs/app.js.
const MIN_IMDB_ROSTER = 1000;

// Svensk titel godtas om den bara innehåller latinska bokstäver, siffror m.m.
const LATINSK = /^[\p{Script=Latin}\p{M}\p{N}\p{P}\p{S}\s]*$/u;

const NYCKEL = process.env.TMDB_API_KEY ||
  fs.readFileSync(path.join(ROT, '.env'), 'utf8').match(/TMDB_API_KEY=(\w+)/)?.[1];
if (!NYCKEL) throw new Error('TMDB_API_KEY saknas');

async function tmdb(sokvag, params = {}) {
  const url = new URL('https://api.themoviedb.org/3' + sokvag);
  for (const [k, v] of Object.entries({ ...params, api_key: NYCKEL })) url.searchParams.set(k, v);
  for (let forsok = 1; ; forsok++) {
    const svar = await fetch(url);
    if (svar.ok) return svar.json();
    if (forsok >= 5 || (svar.status !== 429 && svar.status < 500)) {
      throw new Error(`TMDB ${svar.status} för ${sokvag}`);
    }
    await new Promise(r => setTimeout(r, 1000 * forsok));
  }
}

// Kör uppgift(x) för alla x, högst `antal` åt gången.
async function parallellt(lista, antal, uppgift) {
  let nasta = 0;
  await Promise.all(Array.from({ length: antal }, async () => {
    while (nasta < lista.length) await uppgift(lista[nasta++]);
  }));
}

async function providerIds(typ) {
  const { results } = await tmdb(`/watch/providers/${typ}`, { watch_region: 'SE' });
  const ids = {};
  for (const [kort, namn] of Object.entries(TJANSTER)) {
    const traff = results.find(p => namn.includes(p.provider_name));
    if (traff) ids[kort] = traff.provider_id;
    else console.warn(`Hittar inte ${namn[0]} (${typ}) i TMDB`);
  }
  return ids;
}

// Alla titlar på en tjänst (abonnemang, inte hyra/köp). Max 500 sidor à 20.
async function discover(typ, providerId, extra = {}) {
  const parametrar = {
    watch_region: 'SE',
    with_watch_providers: providerId,
    with_watch_monetization_types: 'flatrate',
    'vote_count.gte': MIN_TMDB_ROSTER,
    sort_by: 'vote_count.desc',
    language: 'sv-SE',
    ...extra,
  };
  const forsta = await tmdb(`/discover/${typ}`, { ...parametrar, page: 1 });
  const sidor = Math.min(forsta.total_pages, 500);
  const resultat = [...forsta.results];
  const resten = Array.from({ length: sidor - 1 }, (_, i) => i + 2);
  await parallellt(resten, 10, async sida => {
    resultat.push(...(await tmdb(`/discover/${typ}`, { ...parametrar, page: sida })).results);
  });
  return resultat;
}

async function imdbBetyg(behovs) {
  const svar = await fetch('https://datasets.imdbws.com/title.ratings.tsv.gz');
  if (!svar.ok) throw new Error(`IMDb ${svar.status}`);
  const text = zlib.gunzipSync(Buffer.from(await svar.arrayBuffer())).toString('utf8');
  const betyg = new Map();
  for (const rad of text.split('\n')) {
    const [id, snitt, roster] = rad.split('\t');
    if (behovs.has(id)) betyg.set(id, [Number(snitt), Number(roster)]);
  }
  return betyg;
}

async function main() {
  const bara = process.argv[2];
  const valda = Object.keys(TJANSTER).filter(k => !bara || TJANSTER[k].includes(bara));
  if (!valda.length) throw new Error(`Okänd tjänst: ${bara}`);

  // Discover filtrerar inte "ingår i abonnemanget" per tjänst (en film som
  // ingår på Netflix men hyrs på Viaplay kommer med för båda). Discover ger
  // därför bara kandidater; tjänsterna bestäms per titel längre ner.
  const titlar = new Map(); // "movie:123" → titel
  const kortFranId = {}; // typ → { providerId: kortnamn }
  for (const typ of ['movie', 'tv']) {
    const providers = await providerIds(typ);
    kortFranId[typ] = Object.fromEntries(Object.entries(providers).map(([k, id]) => [id, k]));
    for (const kort of valda) {
      if (!providers[kort]) continue;
      const romantik = typ === 'tv'
        ? new Set((await discover(typ, providers[kort], { with_keywords: TV_ROMANTIK_KEYWORD })).map(t => t.id))
        : new Set();
      const hittade = await discover(typ, providers[kort]);
      for (const t of hittade) {
        const genrer = Object.entries(GENRER[typ])
          .filter(([, ids]) => ids.some(id => t.genre_ids.includes(id)))
          .map(([namn]) => namn);
        if (romantik.has(t.id)) genrer.unshift('rom');
        if (!genrer.length) continue;
        const nyckel = `${typ}:${t.id}`;
        const titel = titlar.get(nyckel) ?? {
          typ, id: t.id,
          titel: t.title ?? t.name,
          ar: Number((t.release_date ?? t.first_air_date ?? '').slice(0, 4)) || null,
          genrer,
          affisch: t.poster_path,
        };
        titlar.set(nyckel, titel);
      }
      console.log(`${kort} ${typ}: ${hittade.length} titlar från TMDB`);
    }
  }

  // IMDb-id och engelsk titel: sparas i cache/ så att vi bara frågar TMDB om
  // nya titlar. Den engelska titeln används när den svenska inte är i latinsk
  // skrift (TMDB ger originaltiteln när svensk översättning saknas).
  const cache = fs.existsSync(CACHEFIL) ? JSON.parse(fs.readFileSync(CACHEFIL, 'utf8')) : {};
  const saknas = [...titlar.keys()].filter(k => !(k in cache));
  console.log(`Hämtar IMDb-id för ${saknas.length} nya titlar...`);
  await parallellt(saknas, 20, async nyckel => {
    const [typ, id] = nyckel.split(':');
    const d = await tmdb(`/${typ}/${id}`, { language: 'en-US', append_to_response: 'external_ids' });
    cache[nyckel] = [d.external_ids?.imdb_id || null, d.title ?? d.name];
  });
  fs.mkdirSync(path.dirname(CACHEFIL), { recursive: true });
  fs.writeFileSync(CACHEFIL, JSON.stringify(cache));

  const betyg = await imdbBetyg(new Set([...titlar.keys()].map(k => cache[k][0]).filter(Boolean)));

  const medBetyg = [...titlar.keys()].filter(nyckel => {
    const imdb = cache[nyckel][0];
    return imdb && betyg.get(imdb)?.[1] >= MIN_IMDB_ROSTER;
  });

  // Vilka av våra tjänster titeln ingår i (flatrate = i abonnemanget, inte
  // hyra/köp). Hämtas varje körning eftersom utbudet ändras.
  console.log(`Kontrollerar tjänster för ${medBetyg.length} titlar...`);
  const tjanster = new Map();
  await parallellt(medBetyg, 20, async nyckel => {
    const [typ, id] = nyckel.split(':');
    const se = (await tmdb(`/${typ}/${id}/watch/providers`)).results?.SE;
    const valdaHar = (se?.flatrate ?? [])
      .map(p => kortFranId[typ][p.provider_id])
      .filter(k => k && valda.includes(k));
    if (valdaHar.length) tjanster.set(nyckel, [...new Set(valdaHar)]);
  });

  // Kompakt format: korta nycklar för att hålla filen liten.
  const ut = [];
  for (const nyckel of medBetyg) {
    if (!tjanster.has(nyckel)) continue;
    const t = titlar.get(nyckel);
    const [imdb, engelsk] = cache[nyckel];
    const b = betyg.get(imdb);
    ut.push({
      t: LATINSK.test(t.titel) || !engelsk ? t.titel : engelsk, y: t.ar, k: t.typ === 'movie' ? 'f' : 's',
      g: t.genrer, s: tjanster.get(nyckel), i: imdb, r: b[0], v: b[1], p: t.affisch,
    });
  }
  ut.sort((a, b) => b.v - a.v);
  fs.mkdirSync(path.dirname(UTFIL), { recursive: true });
  fs.writeFileSync(UTFIL, JSON.stringify({ uppdaterad: new Date().toISOString(), titlar: ut }));
  console.log(`Skrev ${ut.length} titlar till ${path.relative(ROT, UTFIL)} ` +
    `(${titlar.size - ut.length} saknade IMDb-betyg, hade för få röster eller ingick inte i abonnemanget)`);
}

await main();
