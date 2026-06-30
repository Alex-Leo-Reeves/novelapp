const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const path = require("path");
const { URL } = require("url");

const PORT = Number(process.env.PORT || 3000);
const DATA_DIR = process.env.DATA_DIR || (fs.existsSync("/var/data") ? "/var/data" : path.join(process.cwd(), "server-data"));
const DATA_FILE = path.join(DATA_DIR, "auth.json");
const SITE_DIR = path.join(process.cwd(), "site");
const SESSION_DAYS = 365;
const PASSWORD_ITERATIONS = 210000;
const PASSWORD_KEY_LENGTH = 32;
const PASSWORD_DIGEST = "sha256";

const MIME_TYPES = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".onnx": "application/octet-stream",
  ".svg": "image/svg+xml",
  ".apk": "application/vnd.android.package-archive",
  ".ipa": "application/octet-stream",
  ".msi": "application/octet-stream",
  ".txt": "text/plain; charset=utf-8"
};

function ensureDataFile() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  if (!fs.existsSync(DATA_FILE)) {
    writeData({ users: [], sessions: [] });
  }
}

function readData() {
  ensureDataFile();
  return JSON.parse(fs.readFileSync(DATA_FILE, "utf8"));
}

function writeData(data) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmpFile = `${DATA_FILE}.tmp`;
  fs.writeFileSync(tmpFile, JSON.stringify(data, null, 2));
  fs.renameSync(tmpFile, DATA_FILE);
}

function normalizeEmail(email) {
  return String(email || "").trim().toLowerCase();
}

function publicUser(user) {
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    plan: user.plan || "free",
    billingStatus: user.billingStatus || "none",
    createdAt: user.createdAt
  };
}

function hashPassword(password, salt = crypto.randomBytes(16).toString("base64")) {
  const hash = crypto
    .pbkdf2Sync(String(password), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH, PASSWORD_DIGEST)
    .toString("base64");

  return { salt, hash };
}

function passwordMatches(password, salt, expectedHash) {
  const actual = Buffer.from(hashPassword(password, salt).hash, "base64");
  const expected = Buffer.from(expectedHash, "base64");

  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

function createSession(data, userId) {
  const token = crypto.randomBytes(32).toString("base64url");
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  const now = Date.now();
  const expiresAt = now + SESSION_DAYS * 24 * 60 * 60 * 1000;

  data.sessions = data.sessions.filter((session) => session.expiresAt > now);
  data.sessions.push({
    tokenHash,
    userId,
    createdAt: new Date(now).toISOString(),
    expiresAt
  });

  return token;
}

function getBearerToken(request) {
  const header = request.headers.authorization || "";
  const [scheme, token] = header.split(" ");
  return scheme && scheme.toLowerCase() === "bearer" ? token : null;
}

function findSessionUser(data, token) {
  if (!token) return null;

  const now = Date.now();
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  const session = data.sessions.find(
    (item) => item.tokenHash === tokenHash && item.expiresAt > now
  );

  if (!session) return null;
  return data.users.find((user) => user.id === session.userId) || null;
}

function removeSession(data, token) {
  if (!token) return;
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  data.sessions = data.sessions.filter((session) => session.tokenHash !== tokenHash);
}

function sendJson(response, statusCode, payload) {
  response.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store"
  });
  response.end(JSON.stringify(payload));
}

function sendError(response, statusCode, message) {
  sendJson(response, statusCode, { error: message });
}

function sendApiData(response, statusCode, data) {
  sendJson(response, statusCode, { ok: statusCode >= 200 && statusCode < 300, data, error: null });
}

function sendApiError(response, statusCode, message) {
  sendJson(response, statusCode, { ok: false, data: null, error: message });
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let body = "";

    request.on("data", (chunk) => {
      body += chunk;
      if (body.length > 512 * 1024) {
        request.destroy();
        reject(new Error("Request body is too large."));
      }
    });

    request.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch {
        reject(new Error("Invalid JSON body."));
      }
    });

    request.on("error", reject);
  });
}

function normalizeContentType(type) {
  const raw = String(type || "novels")
    .trim()
    .toLowerCase()
    .replace(/[_\s-]+/g, "");
  if (["movie", "movies", "film", "films"].includes(raw)) return "movies";
  if (["kdrama", "drama", "korean"].includes(raw)) return "kdrama";
  if (["cartoon", "cartoons"].includes(raw)) return "cartoon";
  if (["anime", "manga"].includes(raw)) return raw;
  return "novels";
}

function contentItem({ id, title, subtitle = "", coverUrl = "", detailUrl = "", sourceName = "", kind = "novel", synopsis = "" }) {
  return { id, title, subtitle, coverUrl, detailUrl, sourceName, kind, synopsis };
}

const KNOWN_NOVELS = [
  ["my-vampire-system", "My Vampire System", "JKSManga", "WebNovel", "A weak student gains a vampire system and must survive school, war, and monsters."],
  ["renegade-immortal", "Renegade Immortal", "Er Gen", "WuxiaWorld", "Wang Lin walks a ruthless path through cultivation, revenge, and immortality."],
  ["pursuit-of-truth", "Pursuit of Truth", "Er Gen", "WuxiaWorld", "Su Ming searches for identity and truth in a world of ancient power."],
  ["a-will-eternal", "A Will Eternal", "Er Gen", "WuxiaWorld", "Bai Xiaochun wants to live forever and somehow keeps changing the world around him."],
  ["lord-of-the-mysteries", "Lord of the Mysteries", "Cuttlefish That Loves Diving", "WebNovel", "A Victorian mystery of potions, gods, secret orders, and madness."],
  ["shadow-slave", "Shadow Slave", "Guiltythree", "WebNovel", "Sunny survives nightmare worlds while carrying a dangerous shadow bond."],
  ["omniscient-readers-viewpoint", "Omniscient Reader's Viewpoint", "Sing Shong", "WebNovel", "A reader becomes the only person who knows how the apocalypse story ends."],
  ["martial-peak", "Martial Peak", "Momo", "BoxNovel", "Yang Kai rises through martial worlds in a long cultivation journey."],
  ["coiling-dragon", "Coiling Dragon", "I Eat Tomatoes", "WuxiaWorld", "Linley trains from noble heir to world-shaking warrior and mage."],
  ["the-beginning-after-the-end", "The Beginning After The End", "TurtleMe", "Tapas", "A reincarnated king grows up in a magical world with new bonds and old burdens."]
];

const KNOWN_MANGA = [
  ["solo-leveling", "Solo Leveling", "Chugong", "MangaDex", "The weakest hunter becomes the only player of a hidden leveling system.", "https://uploads.mangadex.org/covers/32c98f54-7aa7-4b53-94a1-1d6b1e8b0f0e/cover.jpg"],
  ["one-piece", "One Piece", "Eiichiro Oda", "MangaDex", "Luffy sails with his crew to find the One Piece.", ""],
  ["jujutsu-kaisen", "Jujutsu Kaisen", "Gege Akutami", "MangaDex", "Curses, sorcerers, and the dangerous vessel of Sukuna.", ""],
  ["chainsaw-man", "Chainsaw Man", "Tatsuki Fujimoto", "MangaDex", "Denji becomes Chainsaw Man and enters a brutal devil-hunting world.", ""]
];

const KNOWN_ANIME = [
  ["solo-leveling-anime", "Solo Leveling", "Action, Fantasy", "AniList", "anime", "anilist:151807", "Hunters fight in gates while Sung Jinwoo receives a leveling system.", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx151807-2dkqG6YlBXsP.jpg"],
  ["one-piece-anime", "One Piece", "Adventure", "AniList", "anime", "anilist:21", "Luffy and the Straw Hats sail the Grand Line.", "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21-YCDoj1EkAxFn.jpg"],
  ["dragon-ball-z", "Dragon Ball Z", "Action", "AniList", "anime", "anilist:813", "Goku and friends defend Earth from powerful enemies.", ""],
  ["my-hero-academia", "My Hero Academia", "Superhero", "AniList", "anime", "anilist:21459", "Izuku Midoriya trains to become a hero in a superpowered world.", ""]
];

const KNOWN_MEDIA = [
  ["toy-story", "Toy Story", "Movie", "TMDB", "movie", "tmdb://movie/862", "A cowboy doll feels threatened when a space ranger toy arrives.", "https://image.tmdb.org/t/p/w500/uXDfjJbdP4ijW5hWSBrPrlKpxab.jpg"],
  ["teen-titans", "Teen Titans", "Cartoon", "TMDB", "cartoon", "tmdb://tv/604", "Young heroes protect Jump City while growing as a team.", "https://image.tmdb.org/t/p/w500/8JfwXjP3iLjyfDfF0ECmS2rN0aA.jpg"],
  ["crash-landing-on-you", "Crash Landing on You", "K-Drama", "TMDB", "kdrama", "tmdb://tv/94796", "A South Korean heiress crash lands in North Korea and meets an officer.", "https://image.tmdb.org/t/p/w500/2u8I9AzgbLGGqE4JdW6uJQO0t5C.jpg"]
];

function fixtureItems(type, query = "") {
  const normalizedType = normalizeContentType(type);
  const raw = normalizedType === "manga" ? KNOWN_MANGA
    : normalizedType === "anime" ? KNOWN_ANIME
    : normalizedType === "kdrama" ? KNOWN_MEDIA.filter((item) => item[4] === "kdrama")
    : normalizedType === "cartoon" ? KNOWN_MEDIA.filter((item) => item[4] === "cartoon")
    : normalizedType === "movies" ? KNOWN_MEDIA.filter((item) => item[4] === "movie")
    : KNOWN_NOVELS;
  const q = String(query || "").trim().toLowerCase();
  return raw
    .filter((item) => !q || `${item[1]} ${item[2]} ${item[3]}`.toLowerCase().includes(q))
    .map((item) => {
      if (normalizedType === "novels") {
        return contentItem({
          id: item[0],
          title: item[1],
          subtitle: item[2],
          sourceName: item[3],
          kind: "novel",
          detailUrl: `novel://${item[0]}`,
          synopsis: item[4],
          coverUrl: `https://dummyimage.com/600x840/111827/42d6b5.png&text=${encodeURIComponent(item[1].slice(0, 20))}`
        });
      }
      if (normalizedType === "manga") {
        return contentItem({
          id: item[0],
          title: item[1],
          subtitle: item[2],
          sourceName: item[3],
          kind: "manga",
          detailUrl: `manga://${item[0]}`,
          synopsis: item[4],
          coverUrl: item[5] || `https://dummyimage.com/600x840/111827/e84d8a.png&text=${encodeURIComponent(item[1].slice(0, 20))}`
        });
      }
      return contentItem({
        id: item[0],
        title: item[1],
        subtitle: item[2],
        sourceName: item[3],
        kind: item[4],
        detailUrl: item[5],
        synopsis: item[6],
        coverUrl: item[7] || `https://dummyimage.com/600x840/111827/fbbf24.png&text=${encodeURIComponent(item[1].slice(0, 20))}`
      });
    });
}

async function fetchWithTimeout(url, options = {}, timeoutMillis = 9000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMillis);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } finally {
    clearTimeout(timeout);
  }
}

async function anilistItems(query, page = 1) {
  const gql = query
    ? `query ($search: String, $page: Int) { Page(page: $page, perPage: 24) { media(search: $search, type: ANIME, sort: SEARCH_MATCH) { id title { romaji english } coverImage { large } genres description(asHtml: false) } } }`
    : `query ($page: Int) { Page(page: $page, perPage: 24) { media(type: ANIME, sort: TRENDING_DESC) { id title { romaji english } coverImage { large } genres description(asHtml: false) } } }`;
  const payload = await fetchWithTimeout("https://graphql.anilist.co", {
    method: "POST",
    headers: { "content-type": "application/json", "accept": "application/json" },
    body: JSON.stringify({ query: gql, variables: { search: query || undefined, page } })
  });
  return (payload.data?.Page?.media || []).map((anime) => contentItem({
    id: `anilist_${anime.id}`,
    title: anime.title?.english || anime.title?.romaji || "Untitled anime",
    subtitle: (anime.genres || []).slice(0, 3).join(", "),
    coverUrl: anime.coverImage?.large || "",
    detailUrl: `anilist:${anime.id}`,
    sourceName: "AniList",
    kind: "anime",
    synopsis: String(anime.description || "").replace(/<[^>]+>/g, "")
  }));
}

async function tmdbItems(type, query, page = 1) {
  const token = process.env.TMDB_READ_ACCESS_TOKEN || "";
  const key = process.env.TMDB_API_KEY || "";
  if (!token && !key) return [];
  const normalizedType = normalizeContentType(type);
  const mediaType = normalizedType === "movies" ? "movie" : "tv";
  const endpoint = query
    ? `https://api.themoviedb.org/3/search/${mediaType}?query=${encodeURIComponent(query)}&page=${page}`
    : `https://api.themoviedb.org/3/${mediaType}/popular?page=${page}`;
  const headers = token ? { authorization: `Bearer ${token}`, accept: "application/json" } : { accept: "application/json" };
  const url = key && !token ? `${endpoint}&api_key=${encodeURIComponent(key)}` : endpoint;
  const payload = await fetchWithTimeout(url, { headers });
  return (payload.results || []).slice(0, 24).map((item) => contentItem({
    id: `tmdb_${mediaType}_${item.id}`,
    title: item.title || item.name || "Untitled",
    subtitle: normalizedType === "kdrama" ? "K-Drama" : normalizedType === "cartoon" ? "Cartoon" : "Movie",
    coverUrl: item.poster_path ? `https://image.tmdb.org/t/p/w500${item.poster_path}` : "",
    detailUrl: `tmdb://${mediaType}/${item.id}`,
    sourceName: "TMDB",
    kind: normalizedType === "movies" ? "movie" : normalizedType,
    synopsis: item.overview || ""
  }));
}

async function mangadexItems(query, page = 1) {
  if (!query) return fixtureItems("manga");
  const url = `https://api.mangadex.org/manga?limit=24&offset=${(page - 1) * 24}&title=${encodeURIComponent(query)}&includes[]=cover_art&availableTranslatedLanguage[]=en&order[relevance]=desc`;
  const payload = await fetchWithTimeout(url);
  return (payload.data || []).map((item) => {
    const title = item.attributes?.title?.en || Object.values(item.attributes?.title || {})[0] || "Untitled manga";
    const coverRel = (item.relationships || []).find((rel) => rel.type === "cover_art");
    const fileName = coverRel?.attributes?.fileName || "";
    return contentItem({
      id: `mangadex_${item.id}`,
      title,
      subtitle: "MangaDex",
      coverUrl: fileName ? `https://uploads.mangadex.org/covers/${item.id}/${fileName}.256.jpg` : "",
      detailUrl: `mangadex:${item.id}`,
      sourceName: "MangaDex",
      kind: "manga",
      synopsis: item.attributes?.description?.en || ""
    });
  });
}

async function contentHome(type, page = 1) {
  const normalizedType = normalizeContentType(type);
  if (normalizedType === "anime") {
    return anilistItems("", page).catch(() => fixtureItems("anime"));
  }
  if (normalizedType === "manga") return fixtureItems("manga");
  if (["kdrama", "cartoon", "movies"].includes(normalizedType)) {
    const tmdb = await tmdbItems(normalizedType, "", page).catch(() => []);
    return tmdb.length ? tmdb : fixtureItems(normalizedType);
  }
  return fixtureItems("novels");
}

async function contentSearch(type, query, page = 1) {
  const normalizedType = normalizeContentType(type);
  if (normalizedType === "anime") {
    const live = await anilistItems(query, page).catch(() => []);
    return live.length ? live : fixtureItems("anime", query);
  }
  if (normalizedType === "manga") {
    const live = await mangadexItems(query, page).catch(() => []);
    return live.length ? live : fixtureItems("manga", query);
  }
  if (["kdrama", "cartoon", "movies"].includes(normalizedType)) {
    const live = await tmdbItems(normalizedType, query, page).catch(() => []);
    return live.length ? live : fixtureItems(normalizedType, query);
  }
  return fixtureItems("novels", query);
}

function syntheticChapters(kind, detailUrl, title = "") {
  const isManga = normalizeContentType(kind) === "manga";
  const count = isManga ? 15 : 100;
  return Array.from({ length: count }, (_, index) => ({
    title: `Chapter ${index + 1}${title ? ` — ${title}` : ""}`,
    url: `${isManga ? "manga-chapter" : "novel-chapter"}://${encodeURIComponent(detailUrl || title || "item")}/${index + 1}`,
    chapterNumber: index + 1
  }));
}

const CHAPTER_SCENES = [
  // 1 — Awakening
  (n, t) => `Chapter ${n} — The Awakening\n\nThe morning light cut through the curtains like a blade when ${t || "the protagonist"} finally opened his eyes. Something had changed in the night — he could feel it in his bones, a strange warmth that had not been there before.\n\n"You're finally awake," said a voice from the doorway. It was Lena, her arms crossed, her expression caught between relief and something harder to name. "We thought we'd lost you."\n\nHe sat up slowly, testing each limb. Three days unconscious after the incident at the northern bridge. Three days the world had continued without him — and he had some catching up to do.\n\nThrough the window, he could see smoke still rising from the direction of the old district. Whatever they had started had not yet finished.`,

  // 2 — The Pursuit
  (n, t) => `Chapter ${n} — The Pursuit\n\nThey ran through streets that had forgotten the sound of peace. ${t ? `In the world of ${t}, ` : ""}every alley was a gamble and every open road was a trap. Kira was three steps ahead, her boots barely touching the wet cobblestones.\n\n"Left!" she barked, and he obeyed without thinking, ducking beneath a rusted pipe just as the patrol rounded the corner behind them.\n\nThe city's old quarter smelled of rain and old copper. Somewhere above them a bell tolled the third hour. They had until dawn to reach the safe house, and dawn was closer than either wanted to admit.\n\nHe pressed his back against the wall and caught his breath. His fingers found the sealed envelope inside his jacket — still there, still dry. Whatever was written on those pages had already gotten two people killed. He intended to make sure their sacrifice had not been wasted.`,

  // 3 — Council of Shadows
  (n, t) => `Chapter ${n} — Council of Shadows\n\nThe chamber beneath the mountain was older than any kingdom that had claimed this land. Seven figures sat around the obsidian table, their faces hidden beneath hoods the colour of deep water.\n\n"The boundary has shifted again," said the eldest, her voice carrying the weight of centuries. "We have perhaps sixty days before the seal breaks entirely."\n\nA heavy silence fell. Outside, far above, the world continued its ordinary business, ignorant of what was being decided in this room.\n\n${t ? `In the tale of ${t}, ` : ""}it was always the hidden councils that changed history — not armies, not kings. Just seven old voices in the dark, choosing who would carry the burden next.\n\n"Send the letter," said the one called Veth. "Tell them it is time."`,

  // 4 — Blade and Blood
  (n, t) => `Chapter ${n} — Blade and Blood\n\nThe duel began at sunrise, as all true duels must.\n\nShe had trained for this for eleven years. Every scar on her hands told a chapter of that preparation. And yet, standing across from him now — this man who had once been her teacher — she felt the old doubt resurface like a stone through ice.\n\nHe moved first. Of course he did. He always moved first.\n\nSteel rang against steel. She pivoted, using his momentum against him, and drove her elbow into the space where his guard opened for a single heartbeat. He absorbed it, rolled, came up with a slash that she barely turned aside. For ten minutes they were nothing but motion and decision.\n\nWhen it ended, neither had won. Both stood breathing hard in the red morning light, and something that had been broken between them felt, fractionally, less broken.\n\n"Tomorrow we try again," he said. It was the closest he could come to an apology.`,

  // 5 — The Village at the Edge
  (n, t) => `Chapter ${n} — The Village at the Edge\n\nThere were places the maps did not show. This was one of them.\n\nThe village sat at the boundary between the forest and the flatlands, too small to have a name worth keeping, the kind of place people passed through and immediately forgot. But ${t || "the traveller"} had not come here to pass through.\n\nAn old man sat mending nets by the well, his hands moving with the automatic patience of someone who had done this ten thousand times. He did not look up when the visitor approached.\n\n"You're the third this season," he said at last. "Looking for the same thing, I expect. The others didn't find it."\n\n"What happened to them?"\n\nThe old man finally looked up. His eyes were the grey of deep water. "They stopped looking." He said it the way you say something obvious, and somehow that made it worse.`,

  // 6 — Weight of Memory
  (n, t) => `Chapter ${n} — Weight of Memory\n\nThe memory came back in pieces, the way things always do when you have tried your hardest to forget them.\n\nShe remembered the smell of the library first — old paper, dust, the particular sweetness of ink that had dried long ago. Then the sound: pages turning, and underneath that, her brother's laughter, which she had not heard in twelve years.\n\n${t ? `In the world of ${t}, ` : ""}the past was not merely the past. It could reach forward and change the present as surely as any weapon.\n\nShe closed the journal she had found in the ruins and held it against her chest. He had written three hundred pages about a plan that could still be carried out — if she was willing to pay the price.\n\nThe question was not whether she could do it. The question was who she would become if she did.`,

  // 7 — The Tournament
  (n, t) => `Chapter ${n} — The Tournament\n\nThree hundred fighters had entered the Cascade Tournament. By the second day, fewer than forty remained.\n\nThe arena floor was carved stone, ancient and pitted with the marks of a thousand previous contests. The crowd above — ten thousand strong — had come for blood and spectacle, and the opening rounds had delivered both in abundance.\n\n${t || "Our fighter"} stretched in the waiting tunnel, listening to the roar from the arena beyond. His next opponent was a giant from the southern provinces who had won four previous tournaments and never once looked concerned while doing it.\n\n"You don't have to win," said his companion, handing over a water flask. "You just have to last long enough for us to find what we need."\n\n"Tell that to my spine," he answered, and walked toward the gate as it opened.`,

  // 8 — Betrayal at Dawn
  (n, t) => `Chapter ${n} — Betrayal at Dawn\n\nHe found the message tucked beneath the door at four in the morning.\n\nThree words. That was all it took. He read them twice and then sat down on the cold floor because standing did not seem possible anymore.\n\nAll the small inconsistencies that he had explained away over the past months — the conversations that stopped when he entered rooms, the journeys with no clear destination, the way she sometimes looked at him with something she was trying to hide — all of it assembled itself now into an ugly and undeniable shape.\n\n${t ? `In ${t}, ` : ""}betrayal was never sudden. It was a slow accumulation of small choices, each one reasonable in isolation, catastrophic in sum.\n\nHe stood, folded the note, and placed it carefully in his jacket pocket. Then he began making his own plans.`,

  // 9 — The Teacher's Secret
  (n, t) => `Chapter ${n} — The Teacher's Secret\n\nMaster Aldric had always kept his left sleeve rolled down. No one had thought to ask why.\n\nThat changed the evening the fire broke out in the training hall and he pulled the apprentices clear through smoke and falling timber. When the sleeve rode up, what they saw stopped them cold: a brand, old and deliberate, the sigil of an order that had been outlawed before any of them were born.\n\n${t ? `The history of ${t} ` : "That history "} ran deeper than the textbooks admitted. There were whole chapters that powerful people preferred to keep sealed.\n\n"Ask your question," said Aldric, rolling the sleeve back down with the unhurried dignity of a man who had prepared for this conversation for twenty years.\n\n"What are you?" was the only question any of them could find.\n\nHe smiled — not kindly, but honestly. "I am the thing that made the current peace possible. And the thing that will need to unmake it, before this is over."`,

  // 10 — City of Glass
  (n, t) => `Chapter ${n} — City of Glass\n\nThe capital was beautiful the way a blade is beautiful — functional, deliberate, cold.\n\nFrom the observation deck at the top of the Archon's Tower, the whole city spread out like a circuit board, its avenues carrying people and goods in patterns that someone, somewhere, had decided were optimal. Nothing here happened by accident. Nothing was allowed to.\n\n${t || "The visitor"} had grown up in a place where things grew crooked and wild. She found the capital's perfection quietly unbearable.\n\n"What do you think?" asked her escort, clearly expecting admiration.\n\n"I think," she said carefully, "that someone worked very hard to make sure there is nowhere to hide."\n\nHer escort's smile didn't change, but something behind his eyes did. She had just told him more about herself than she intended.`,

  // 11 — Ancient Contract
  (n, t) => `Chapter ${n} — Ancient Contract\n\nThe contract had been written in three languages, two of which no living person could read.\n\nIt had been buried beneath the foundation stone of a building that no longer existed, in a city that had changed its name twice since the document was sealed. And yet here it was, unrolled on the table between them, stubbornly intact.\n\n"This is binding," said the archivist, his voice hushed. "If it says what I think it says, the terms are still in effect."\n\n${t ? `In the lore of ${t}, ` : ""}the old agreements carried a weight that newer laws could not override. This was both the foundation of civilization and its most persistent headache.\n\n"Can we break it?" asked the commander.\n\nThe archivist touched the edge of the parchment with one careful finger. "Everything can be broken. The question is always what breaks with it."`,

  // 12 — Voice from the Dark
  (n, t) => `Chapter ${n} — Voice from the Dark\n\nThe voice in his head had been there since the accident. He had not told anyone.\n\nIt was not threatening — that was the strange part. It answered questions he had not finished asking and went quiet when he needed silence. It had warned him twice now about dangers that had proven real.\n\n${t ? `Within the events of ${t}, ` : ""}the boundary between internal and external, between imagination and contact, had always been thinner than the textbooks suggested.\n\n"What are you?" he asked it one evening, the room empty, the night quiet outside.\n\nThe voice considered this for what felt like a long time. When it answered, it said something that he would spend the next six chapters trying to understand: "I am what remains of a decision you have not yet made."`,

  // 13 — The Bridge
  (n, t) => `Chapter ${n} — The Bridge\n\nThey met at the midpoint of the bridge, as they always met — in the place that belonged to neither side.\n\nThe river below was high and angry with snowmelt from the mountains. It did not care about their negotiations.\n\n${t ? `The conflict that drove ${t} ` : "This conflict "} had its roots in a grievance that everyone agreed was legitimate and no one agreed on how to address. Such things had a way of lasting generations.\n\n"I am authorized to offer the following terms," she began, unfolding the paper. She read for three minutes. When she finished, the silence between them lasted almost as long.\n\n"That is not enough," said the man across from her.\n\n"I know," she said. "But it is what I have. The question is whether we can build something larger from this starting point, or whether we leave this bridge and don't return."\n\nHe looked at the river. She looked at him. The bridge held them both, impartial as always.`,

  // 14 — The Inheritance
  (n, t) => `Chapter ${n} — The Inheritance\n\nThe old woman left behind four things: a house that needed substantial work, a debt that would require selling the house to settle, a letter explaining her reasoning in detail, and a key whose lock no one could find.\n\nHer granddaughter read the letter three times, sitting in the dusty kitchen where she had spent every summer of her childhood. ${t ? `In the world of ${t}, ` : ""}inheritances were rarely as simple as the word suggested. What passed from one generation to the next was not always property.\n\nThe fourth item — the key — was small and old, made of a metal she did not recognize, with teeth that formed a pattern she had seen somewhere before.\n\nShe put it on the table and looked at it for a long time.\n\nThen she got up, found her grandmother's personal journals, and started at the beginning.`,

  // 15 — The Last Night
  (n, t) => `Chapter ${n} — The Last Night\n\nNone of them said it was the last night. They didn't need to.\n\nThey sat around the fire in the old camp — the same one they had made on that first desperate evening, years ago now, when none of them had known each other's names. The same stars watched from above. The same sounds came from the forest beyond.\n\nEverything was different.\n\n${t ? `This is the shape of all good stories, including ${t}: ` : "This is the shape of every ending: "}the cast changes without your noticing, one slow degree at a time, until the day you look around and realize that the people you began with and the people beside you now are both, somehow, the same people — just truer.\n\n"Tomorrow," said the one called Ivar, poking the fire.\n\n"Tomorrow," the others agreed.\n\nThey stayed by the fire until it burned itself to embers. No one wanted to be the first to sleep.`,

  // 16 — Beneath the City
  (n, t) => `Chapter ${n} — Beneath the City\n\nThe tunnel system beneath the old city was not on any official map. It predated the city by at least four hundred years and had been used, over the centuries, for purposes that each era preferred to forget.\n\nThey moved by lantern light, single file, the ceiling close enough that the tallest of them had to walk bent. ${t ? `Exploring the hidden depths of ${t}, ` : ""}she counted doorways as they passed — seventeen before Maren stopped and put her hand flat against an unmarked wall.\n\n"Here."\n\n"How do you know?"\n\n"Because I helped build it." She said it simply, without drama, and that made it twice as strange. She had been born forty years after this tunnel was supposedly constructed. "Some things take longer to explain than we have time for right now. Help me with this panel."`,

  // 17 — The Long Road
  (n, t) => `Chapter ${n} — The Long Road\n\nDay seventeen of the crossing. The horizon was exactly the same as day one.\n\nThe desert did not hate travellers — it was simply indifferent to them, which was worse. It offered no drama, no sudden dangers, only the steady grinding patience of distance.\n\n${t ? `In ${t}, ` : ""}the long stretches between events were where character was actually formed. Anyone could be brave for an hour. Forty days in the wasteland with the same four people, dwindling supplies, and no news from the world behind you — that was the real examination.\n\n"Seventeen days in and you're still talking to yourself," said Petra, dropping into step beside him.\n\n"I'm narrating."\n\n"To who?"\n\nHe looked at the empty sky ahead. "Anyone who's listening, I suppose."`,

  // 18 — A Door in the Mountain
  (n, t) => `Chapter ${n} — A Door in the Mountain\n\nThe door had no handle, no seam, no visible mechanism of any kind. It was simply a rectangle of smooth stone that was not quite the same colour as the mountain around it.\n\n${t ? `In the geography of ${t}, ` : ""}the most important locations were always the ones that resisted easy access. As if the world itself sorted for commitment.\n\n"We've been staring at it for two hours," said Orin.\n\n"Some things require patience."\n\n"Some things require dynamite."\n\nShe was about to answer when the door opened — not outward, not inward, but simply ceased to be a barrier, as though it had been waiting for exactly this conversation and had decided they were entertaining enough to admit.\n\nBeyond it was light, and warmth, and the smell of something cooking.\n\nOrin looked at her. "All right. Patience."`,

  // 19 — Endgame
  (n, t) => `Chapter ${n} — Endgame\n\nThe pieces were all on the board. The moves that remained were few.\n\n${t ? `Everything in ${t} ` : "Everything "} had been leading here — every detour, every apparent dead end, every sacrifice that had felt random at the time. Seen from this angle, the shape of it was almost elegant. Almost.\n\n"There are three possible outcomes," she said, laying out the maps. "In the first, we win but lose the northern territories permanently. In the second, we preserve everything but someone in this room does not survive the method. In the third—" she paused.\n\n"In the third?" prompted the commander.\n\n"In the third, we trust the plan we made in the beginning, before we knew how complicated it would become, and we follow it through."\n\nSilence.\n\n"The beginning plan was made with incomplete information," said someone at the back.\n\n"Yes," she agreed. "So was this conversation. So is every decision anyone has ever made. That is not a reason to stop deciding."`,

  // 20 — After
  (n, t) => `Chapter ${n} — After\n\nAfterward, when people asked what it had been like, none of them could agree on an answer.\n\nIt had been terrifying, said one. It had been clarifying, said another. It had been long, said a third, and somehow that felt most accurate.\n\n${t ? `The world of ${t} ` : "The world "} had not ended, which was often treated as a simple fact but was actually a small miracle — the product of a hundred decisions made correctly in sequence by people who had been tired and frightened and had no guarantee things would work.\n\nThe ruins were being cleared. New buildings were going up alongside them, built from the same stone. There was an argument about what the new central square should be called, which meant the city was returning to normal.\n\nShe sat on the steps of what had been the old archive, now roofless and optimistically scaffolded, and watched the work continue.\n\nSomeone would write all of this down eventually. She hoped they got it right.`
];

function syntheticChapterText(chapterUrl, title = "") {
  const number = Number(String(chapterUrl || "").split("/").pop()) || 1;
  const sceneIndex = (number - 1) % CHAPTER_SCENES.length;
  const scene = CHAPTER_SCENES[sceneIndex](number, title);
  // Add a short next-chapter teaser
  const teaser = `\n\n— End of Chapter ${number} —\n\nChapter ${number + 1} continues the story. Tap the next chapter in the list to keep reading.`;
  return scene + teaser;
}

function syntheticMangaPages(chapterUrl) {
  const number = Number(String(chapterUrl || "").split("/").pop()) || 1;
  // Different colour palette per chapter so pages are visually distinct
  const palettes = [
    { bg: "0d0d1a", fg: "4cc9f0", accent: "f72585" },
    { bg: "1a0d2e", fg: "fee440", accent: "00bbf9" },
    { bg: "0d1a0d", fg: "06d6a0", accent: "ffd166" },
    { bg: "1a0d0d", fg: "ef476f", accent: "ffd166" },
    { bg: "0d1a1a", fg: "118ab2", accent: "06d6a0" },
    { bg: "1a1a0d", fg: "f4a261", accent: "e76f51" },
    { bg: "12121f", fg: "a8dadc", accent: "e63946" },
    { bg: "1f1205", fg: "ffb703", accent: "fb8500" },
  ];
  const p = palettes[(number - 1) % palettes.length];
  return Array.from({ length: 14 }, (_, index) => {
    const pageNum = index + 1;
    const text = encodeURIComponent(`Ch ${number}  ·  Pg ${pageNum}`);
    return `https://dummyimage.com/900x1350/${p.bg}/${p.fg}.png&text=${text}`;
  });
}

function watchRoute(kind, title, detailUrl) {
  const normalizedKind = normalizeContentType(kind);
  const tmdbMatch = /^tmdb:\/\/([^/]+)\/(\d+)/.exec(String(detailUrl || ""));
  if (tmdbMatch) {
    const mediaType = tmdbMatch[1] === "movie" || normalizedKind === "movies" ? "movie" : "tv";
    const id = tmdbMatch[2];
    return {
      route: "embed",
      url: mediaType === "movie" ? `https://vidsrc.to/embed/movie/${id}` : `https://vidsrc.to/embed/tv/${id}/1/1`,
      provider: "VidSrc",
      title: title || "Watch"
    };
  }
  if (/^https?:\/\//i.test(String(detailUrl || ""))) {
    const direct = /\.(m3u8|mp4|mpd)(\?|$)/i.test(detailUrl);
    return {
      route: direct ? "direct" : "embed",
      url: detailUrl,
      provider: direct ? "Direct" : "Web",
      title: title || "Watch"
    };
  }
  return {
    route: "unavailable",
    url: "",
    provider: "",
    title: title || "Unavailable",
    message: "No playable provider was available for this title."
  };
}

async function handleContentApi(request, response, pathname, url) {
  try {
    if (request.method === "GET" && pathname === "/api/content/home") {
      const type = normalizeContentType(url.searchParams.get("type"));
      const page = Math.max(1, Number(url.searchParams.get("page") || 1));
      return sendApiData(response, 200, { items: await contentHome(type, page) });
    }
    if (request.method === "GET" && pathname === "/api/content/search") {
      const type = normalizeContentType(url.searchParams.get("type"));
      const query = String(url.searchParams.get("q") || "").trim();
      const page = Math.max(1, Number(url.searchParams.get("page") || 1));
      return sendApiData(response, 200, { items: await contentSearch(type, query, page) });
    }
    if (request.method === "POST" && pathname === "/api/content/chapters") {
      const body = await readBody(request);
      return sendApiData(response, 200, { chapters: syntheticChapters(body.kind, body.detailUrl, body.title) });
    }
    if (request.method === "POST" && pathname === "/api/content/chapter-text") {
      const body = await readBody(request);
      return sendApiData(response, 200, { text: syntheticChapterText(body.chapterUrl, body.title) });
    }
    if (request.method === "POST" && pathname === "/api/content/manga-pages") {
      const body = await readBody(request);
      return sendApiData(response, 200, { pages: syntheticMangaPages(body.chapterUrl) });
    }
    if (request.method === "POST" && pathname === "/api/content/watch-route") {
      const body = await readBody(request);
      return sendApiData(response, 200, watchRoute(body.kind, body.title, body.detailUrl));
    }
    return sendApiError(response, 404, "Content API route not found.");
  } catch (error) {
    return sendApiError(response, 400, error.message || "Content request failed.");
  }
}

async function handleRegister(request, response) {
  const body = await readBody(request);
  const username = String(body.username || "").trim();
  const email = normalizeEmail(body.email);
  const password = String(body.password || "");

  if (username.length < 2) return sendError(response, 400, "Username must be at least 2 characters.");
  if (!email.includes("@") || !email.includes(".")) return sendError(response, 400, "Enter a valid email.");
  if (password.length < 6) return sendError(response, 400, "Password must be at least 6 characters.");

  const data = readData();
  if (data.users.some((user) => user.email === email)) {
    return sendError(response, 409, "An account with this email already exists.");
  }

  const passwordRecord = hashPassword(password);
  const user = {
    id: crypto.randomUUID(),
    username,
    email,
    passwordSalt: passwordRecord.salt,
    passwordHash: passwordRecord.hash,
    plan: "free",
    billingStatus: "none",
    state: {
      favorites: [],
      readHistory: [],
      watchHistory: [],
      updatedAt: Date.now()
    },
    createdAt: new Date().toISOString()
  };
  data.users.push(user);
  const token = createSession(data, user.id);
  writeData(data);

  return sendJson(response, 201, { token, user: publicUser(user) });
}

async function handleLogin(request, response) {
  const body = await readBody(request);
  const email = normalizeEmail(body.email);
  const password = String(body.password || "");
  const data = readData();
  const user = data.users.find((item) => item.email === email);

  if (!user || !passwordMatches(password, user.passwordSalt, user.passwordHash)) {
    return sendError(response, 401, "Email or password is incorrect.");
  }

  const token = createSession(data, user.id);
  writeData(data);

  return sendJson(response, 200, { token, user: publicUser(user) });
}

function handleMe(request, response) {
  const data = readData();
  const user = findSessionUser(data, getBearerToken(request));

  if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
  return sendJson(response, 200, { user: publicUser(user) });
}

function handleLogout(request, response) {
  const data = readData();
  removeSession(data, getBearerToken(request));
  writeData(data);
  return sendJson(response, 200, { ok: true });
}

function normalizeUserState(rawState) {
  const state = rawState && typeof rawState === "object" ? rawState : {};
  const now = Date.now();
  const updatedAt = typeof state.updatedAt === "number"
    ? state.updatedAt
    : typeof state.updatedAt === "string"
      ? (Date.parse(state.updatedAt) || now)
      : now;
  return {
    favorites: Array.isArray(state.favorites) ? state.favorites.slice(0, 250) : [],
    readHistory: Array.isArray(state.readHistory) ? state.readHistory.slice(0, 100) : [],
    watchHistory: Array.isArray(state.watchHistory) ? state.watchHistory.slice(0, 100) : [],
    updatedAt
  };
}

function handleGetUserState(request, response) {
  const data = readData();
  const user = findSessionUser(data, getBearerToken(request));

  if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
  user.state = normalizeUserState(user.state);
  return sendJson(response, 200, {
    user: publicUser(user),
    state: user.state
  });
}

async function handlePutUserState(request, response) {
  const data = readData();
  const user = findSessionUser(data, getBearerToken(request));

  if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
  const body = await readBody(request);
  user.state = normalizeUserState(body.state || body);
  user.state.updatedAt = Date.now();
  writeData(data);
  return sendJson(response, 200, {
    user: publicUser(user),
    state: user.state
  });
}

async function handleApi(request, response, pathname) {
  try {
    const requestUrl = new URL(request.url, `http://${request.headers.host || "localhost"}`);
    if (pathname.startsWith("/api/content/")) {
      return await handleContentApi(request, response, pathname, requestUrl);
    }
    if (request.method === "POST" && pathname === "/api/auth/register") {
      return await handleRegister(request, response);
    }
    if (request.method === "POST" && pathname === "/api/auth/login") {
      return await handleLogin(request, response);
    }
    if (request.method === "GET" && pathname === "/api/auth/me") {
      return handleMe(request, response);
    }
    if (request.method === "POST" && pathname === "/api/auth/logout") {
      return handleLogout(request, response);
    }
    if (request.method === "GET" && pathname === "/api/user/state") {
      return handleGetUserState(request, response);
    }
    if (request.method === "PUT" && pathname === "/api/user/state") {
      return await handlePutUserState(request, response);
    }

    return sendError(response, 404, "API route not found.");
  } catch (error) {
    return sendError(response, 400, error.message || "Request failed.");
  }
}

function serveStatic(request, response, pathname) {
  if (pathname === "/assets/kokoro/model_quantized.onnx") {
    const modelPath = path.join(process.cwd(), "kokoro-assets", "kokoro", "model_quantized.onnx");
    if (fs.existsSync(modelPath) && fs.statSync(modelPath).isFile()) {
      const stat = fs.statSync(modelPath);
      const etag = `"${stat.size.toString(16)}-${Math.floor(stat.mtimeMs).toString(16)}"`;
      const commonHeaders = {
        "content-type": "application/octet-stream",
        "cache-control": "public, max-age=31536000, immutable",
        "accept-ranges": "bytes",
        "etag": etag
      };

      if (request.method === "HEAD") {
        response.writeHead(200, {
          ...commonHeaders,
          "content-length": stat.size
        });
        response.end();
        return;
      }

      const range = request.headers.range;
      if (range) {
        const match = /^bytes=(\d*)-(\d*)$/.exec(range);
        if (!match) {
          response.writeHead(416, {
            ...commonHeaders,
            "content-range": `bytes */${stat.size}`
          });
          response.end();
          return;
        }

        const start = match[1] ? Number(match[1]) : 0;
        const end = match[2] ? Number(match[2]) : stat.size - 1;
        if (start >= stat.size || end >= stat.size || start > end) {
          response.writeHead(416, {
            ...commonHeaders,
            "content-range": `bytes */${stat.size}`
          });
          response.end();
          return;
        }

        response.writeHead(206, {
          ...commonHeaders,
          "content-length": end - start + 1,
          "content-range": `bytes ${start}-${end}/${stat.size}`
        });
        fs.createReadStream(modelPath, { start, end }).pipe(response);
        return;
      }

      response.writeHead(200, {
        ...commonHeaders,
        "content-length": stat.size
      });
      fs.createReadStream(modelPath).pipe(response);
      return;
    }
  }

  const safePath = path
    .normalize(decodeURIComponent(pathname))
    .replace(/^(\.\.(\/|\\|$))+/, "");
  const requestedPath = safePath === "/" ? "/index.html" : safePath;
  const filePath = path.join(SITE_DIR, requestedPath);
  const resolvedPath = fs.existsSync(filePath) && fs.statSync(filePath).isFile()
    ? filePath
    : path.join(SITE_DIR, "index.html");
  const ext = path.extname(resolvedPath).toLowerCase();
  const stat = fs.statSync(resolvedPath);

  response.writeHead(200, {
    "content-type": MIME_TYPES[ext] || "application/octet-stream",
    "content-length": stat.size
  });
  if (request.method === "HEAD") {
    response.end();
    return;
  }
  fs.createReadStream(resolvedPath).pipe(response);
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);

  if (url.pathname === "/health") {
    return sendJson(response, 200, { ok: true });
  }

  // Kokoro manifest — lets the iOS app discover the model URL and size without
  // needing to hard-code them. Returns a minimal manifest even when the model
  // file is not present so the iOS installer can fall back to Apple TTS.
  if (url.pathname === "/assets/kokoro/manifest.json") {
    const modelPath = path.join(process.cwd(), "kokoro-assets", "kokoro", "model_quantized.onnx");
    const modelExists = fs.existsSync(modelPath) && fs.statSync(modelPath).isFile();
    const sizeBytes = modelExists ? fs.statSync(modelPath).size : 0;
    response.writeHead(200, {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "public, max-age=86400",
      "access-control-allow-origin": "*"
    });
    response.end(JSON.stringify({
      version: "1.0",
      minimumAppVersionCode: 1,
      model: {
        fileName: "model_quantized.onnx",
        url: `https://${request.headers.host || "novelapp1.onrender.com"}/assets/kokoro/model_quantized.onnx`,
        sizeBytes,
        sha256: "skip"
      }
    }));
    return;
  }

  if (url.pathname.startsWith("/api/")) {
    return handleApi(request, response, url.pathname);
  }

  return serveStatic(request, response, url.pathname);
});

ensureDataFile();
server.listen(PORT, () => {
  console.log(`NovelApp server listening on ${PORT}`);
});
