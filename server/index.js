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
  const count = isManga ? 12 : 80;
  return Array.from({ length: count }, (_, index) => ({
    title: `${isManga ? "Chapter" : "Chapter"} ${index + 1}${title ? ` - ${title}` : ""}`,
    url: `${isManga ? "manga-chapter" : "novel-chapter"}://${encodeURIComponent(detailUrl || title || "item")}/${index + 1}`,
    chapterNumber: index + 1
  }));
}

function syntheticChapterText(chapterUrl, title = "this story") {
  const number = Number(String(chapterUrl || "").split("/").pop()) || 1;
  return [
    `Chapter ${number}`,
    "",
    `The room was quiet when the story of ${title} began to move again. A low breath passed through the dark, and every step carried the weight of a decision that could not be taken back.`,
    "",
    `"We keep going," she said, voice steady even though the walls trembled.`,
    "",
    `He nodded, tightened his grip, and ran forward. Steel flashed. Dust rose. The world narrowed to heartbeat, breath, and the next impossible strike.`,
    "",
    `For now, this generated chapter keeps the iPhone reader, highlighting, progress bar, and narration pipeline working while live source extraction is routed through Render.`
  ].join("\n");
}

function syntheticMangaPages(chapterUrl) {
  const number = Number(String(chapterUrl || "").split("/").pop()) || 1;
  return Array.from({ length: 8 }, (_, index) => {
    const text = encodeURIComponent(`Chapter ${number} Page ${index + 1}`);
    return `https://dummyimage.com/900x1400/05070d/f8fafc.png&text=${text}`;
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
  if (url.pathname.startsWith("/api/")) {
    return handleApi(request, response, url.pathname);
  }

  return serveStatic(request, response, url.pathname);
});

ensureDataFile();
server.listen(PORT, () => {
  console.log(`NovelApp server listening on ${PORT}`);
});
