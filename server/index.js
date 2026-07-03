const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const path = require("path");
const { URL } = require("url");
const swiftNovelScrapers = require("./swift-novel-scrapers");
let consumetExtensions = null;
try {
  consumetExtensions = require("@consumet/extensions");
} catch (error) {
  console.warn("[content] Consumet extensions unavailable:", error.message || error);
}

const PORT = Number(process.env.PORT || 3000);
const DATA_DIR = process.env.DATA_DIR || (fs.existsSync("/var/data") ? "/var/data" : path.join(process.cwd(), "server-data"));
const DATA_FILE = path.join(DATA_DIR, "auth.json");
const SITE_DIR = path.join(process.cwd(), "site");
const OMSS_BASE_URL = cleanBaseUrl(process.env.OMSS_BASE_URL || process.env.OMSS_API_BASE_URL || "");
const VIDLINK_RESOLVER_BASE_URL = cleanBaseUrl(process.env.VIDLINK_RESOLVER_BASE_URL || process.env.VIDLINK_API_BASE_URL || "");
const CINEPRO_BASE_URL = cleanBaseUrl(process.env.CINEPRO_BASE_URL || process.env.CINEHUB_BASE_URL || process.env.CINEPRO_API_BASE_URL || "");
const CONSUMET_BASE_URL = cleanBaseUrl(process.env.CONSUMET_BASE_URL || process.env.CONSUMET_API_BASE_URL || "");
const SUPABASE_URL = cleanBaseUrl(process.env.SUPABASE_URL || process.env.supabase_url || process.env.project_url || "");
const SUPABASE_SECRET_KEY = String(process.env.SUPABASE_SECRET_KEY || process.env.supabase_secret_key || process.env.service_role_key || "").trim();
const FLUTTERWAVE_SECRET_KEY = String(process.env.FLUTTERWAVE_SECRET_KEY || process.env.flutterwave_secret_key || "").trim();
const FLUTTERWAVE_SECRET_HASH = String(process.env.FLUTTERWAVE_SECRET_HASH || process.env.flutterwave_secret_hash || "").trim();
const PUBLIC_APP_URL = cleanBaseUrl(process.env.PUBLIC_APP_URL || process.env.RENDER_EXTERNAL_URL || "https://novelapp1.onrender.com");
const SESSION_DAYS = 365;
const PASSWORD_ITERATIONS = 210000;
const PASSWORD_KEY_LENGTH = 32;
const PASSWORD_DIGEST = "sha256";
const DEFAULT_PREMIUM_PLAN_ID = "premium_3_devices";
const PREMIUM_SEED_EMAIL = "mike@mike.com";
const PREMIUM_SEED_USERNAME = "mike";
const PREMIUM_SEED_PASSWORD = "123456";
const MOVIE_FREE_PREVIEW_MS = 20 * 60 * 1000;
const EPISODIC_FREE_FRACTION = 0.2;
let legacySupabaseMigrationDone = false;

const BILLING_PLANS = {
  free: {
    id: "free",
    label: "Free",
    amount: 0,
    currency: "NGN",
    maxDevices: 2,
    premium: false,
    description: "Free preview access and up to 2 signed-in devices."
  },
  premium_3_devices: {
    id: "premium_3_devices",
    label: "Premium 3 devices",
    amount: 1000,
    currency: "NGN",
    maxDevices: 3,
    premium: true,
    description: "Full movies, cartoons, K-drama, and up to 3 signed-in devices."
  },
  premium_unlimited: {
    id: "premium_unlimited",
    label: "Premium unlimited",
    amount: 4000,
    currency: "NGN",
    maxDevices: null,
    premium: true,
    description: "Full access and unlimited signed-in devices."
  }
};

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

const CONSUMET_ANIME_PROVIDERS = {
  hianime: {
    label: "HiAnime",
    path: "hianime",
    className: "Hianime",
    servers: ["VidCloud", "MegaCloud", "VidStreaming", "StreamSB", "StreamTape", "UpCloud"]
  },
  animekai: {
    label: "AnimeKai",
    path: "animekai",
    className: "AnimeKai",
    servers: ["MegaCloud", "VidCloud", "VidStreaming", "StreamSB", "StreamTape", "UpCloud"]
  },
  kickassanime: {
    label: "KickAssAnime",
    path: "kickassanime",
    className: "KickAssAnime",
    servers: ["BirdStream", "DuckStream", "VidStreaming"]
  },
  animesaturn: {
    label: "AnimeSaturn",
    path: "animesaturn",
    className: "AnimeSaturn",
    servers: []
  },
  animeunity: {
    label: "AnimeUnity",
    path: "animeunity",
    className: "AnimeUnity",
    servers: []
  },
  animesama: {
    label: "AnimeSama",
    path: "animesama",
    className: "AnimeSama",
    servers: []
  },
  consumetpahe: {
    label: "Consumet Pahe",
    path: "animepahe",
    className: "AnimePahe",
    servers: []
  }
};

const CONSUMET_ANIME_ALIASES = {
  zoro: "hianime",
  "zoro/hianime": "hianime",
  "zoro-hianime": "hianime",
  aniwatch: "hianime",
  anix: "animekai",
  saturn: "animesaturn",
  unity: "animeunity",
  sama: "animesama",
  pahe: "consumetpahe",
  animepaheconsumet: "consumetpahe",
  "consumet-pahe": "consumetpahe",
  kaa: "kickassanime",
  "kickass-anime": "kickassanime"
};

function cleanBaseUrl(value) {
  return String(value || "").trim().replace(/\/+$/, "");
}

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

function supabaseEnabled() {
  return Boolean(SUPABASE_URL && SUPABASE_SECRET_KEY);
}

async function supabaseRequest(pathname, { method = "GET", body, prefer } = {}) {
  const headers = {
    apikey: SUPABASE_SECRET_KEY,
    authorization: `Bearer ${SUPABASE_SECRET_KEY}`,
    accept: "application/json"
  };
  if (body !== undefined) headers["content-type"] = "application/json";
  if (prefer) headers.prefer = prefer;
  const response = await fetch(`${SUPABASE_URL}/rest/v1/${pathname}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Supabase ${method} ${pathname} failed (${response.status}): ${text || response.statusText}`);
  }
  return text ? JSON.parse(text) : null;
}

function dbUserToApp(row) {
  if (!row) return null;
  return {
    id: row.id,
    username: row.username,
    email: row.email,
    passwordSalt: row.password_salt,
    passwordHash: row.password_hash,
    recoverySecretHash: row.recovery_secret_hash,
    plan: row.plan || "free",
    billingStatus: row.billing_status || "none",
    paidUntil: row.paid_until || null,
    createdAt: row.created_at
  };
}

function appUserToDb(user) {
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    password_salt: user.passwordSalt,
    password_hash: user.passwordHash,
    recovery_secret_hash: user.recoverySecretHash || null,
    plan: user.plan || "free",
    billing_status: user.billingStatus || "none",
    paid_until: user.paidUntil || null,
    created_at: user.createdAt || new Date().toISOString()
  };
}

async function findSupabaseUserByEmail(email) {
  const rows = await supabaseRequest(`novel_users?email=eq.${encodeURIComponent(email)}&select=*`);
  return dbUserToApp(rows?.[0]);
}

async function findSupabaseUserByRecoveryHash(recoverySecretHash) {
  const rows = await supabaseRequest(`novel_users?recovery_secret_hash=eq.${encodeURIComponent(recoverySecretHash)}&select=*`);
  return dbUserToApp(rows?.[0]);
}

async function findSupabaseUserById(id) {
  const rows = await supabaseRequest(`novel_users?id=eq.${encodeURIComponent(id)}&select=*`);
  return dbUserToApp(rows?.[0]);
}

async function insertSupabaseUser(user) {
  const rows = await supabaseRequest("novel_users", {
    method: "POST",
    prefer: "return=representation",
    body: appUserToDb(user)
  });
  await supabaseRequest("novel_user_states?on_conflict=user_id", {
    method: "POST",
    prefer: "resolution=merge-duplicates",
    body: {
      user_id: user.id,
      state: normalizeUserState(user.state),
      updated_at: new Date().toISOString()
    }
  });
  return dbUserToApp(rows?.[0]);
}

async function updateSupabaseUser(id, patch) {
  const rows = await supabaseRequest(`novel_users?id=eq.${encodeURIComponent(id)}`, {
    method: "PATCH",
    prefer: "return=representation",
    body: patch
  });
  return dbUserToApp(rows?.[0]);
}

async function createSupabaseSession(userId) {
  const token = crypto.randomBytes(32).toString("base64url");
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  const now = Date.now();
  const expiresAt = new Date(now + SESSION_DAYS * 24 * 60 * 60 * 1000).toISOString();
  await supabaseRequest("novel_sessions", {
    method: "POST",
    body: {
      token_hash: tokenHash,
      user_id: userId,
      created_at: new Date(now).toISOString(),
      expires_at: expiresAt
    }
  });
  return token;
}

async function countSupabaseActiveSessions(userId) {
  const rows = await supabaseRequest(
    `novel_sessions?user_id=eq.${encodeURIComponent(userId)}&expires_at=gt.${encodeURIComponent(new Date().toISOString())}&select=token_hash`
  );
  return Array.isArray(rows) ? rows.length : 0;
}

async function findSupabaseSessionUser(token) {
  if (!token) return null;
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  const rows = await supabaseRequest(
    `novel_sessions?token_hash=eq.${encodeURIComponent(tokenHash)}&expires_at=gt.${encodeURIComponent(new Date().toISOString())}&select=user_id`
  );
  const userId = rows?.[0]?.user_id;
  return userId ? findSupabaseUserById(userId) : null;
}

async function removeSupabaseSession(token) {
  if (!token) return;
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  await supabaseRequest(`novel_sessions?token_hash=eq.${encodeURIComponent(tokenHash)}`, { method: "DELETE" });
}

async function getSupabaseUserState(userId) {
  const rows = await supabaseRequest(`novel_user_states?user_id=eq.${encodeURIComponent(userId)}&select=state`);
  return normalizeUserState(rows?.[0]?.state);
}

async function putSupabaseUserState(userId, state) {
  const merged = normalizeUserState(state);
  await supabaseRequest("novel_user_states?on_conflict=user_id", {
    method: "POST",
    prefer: "resolution=merge-duplicates",
    body: {
      user_id: userId,
      state: merged,
      updated_at: new Date().toISOString()
    }
  });
  return merged;
}

function seedPremiumUserInFile(data) {
  const email = PREMIUM_SEED_EMAIL;
  const existing = data.users.find((user) => user.email === email);
  if (existing) {
    existing.username = existing.username || PREMIUM_SEED_USERNAME;
    existing.plan = "premium";
    existing.billingStatus = "active";
    existing.paidUntil = null;
    return existing;
  }
  const passwordRecord = hashPassword(PREMIUM_SEED_PASSWORD);
  const user = {
    id: crypto.randomUUID(),
    username: PREMIUM_SEED_USERNAME,
    email,
    passwordSalt: passwordRecord.salt,
    passwordHash: passwordRecord.hash,
    recoverySecretHash: null,
    plan: "premium",
    billingStatus: "active",
    paidUntil: null,
    state: normalizeUserState({}),
    createdAt: new Date().toISOString()
  };
  data.users.push(user);
  return user;
}

async function ensurePremiumSeedUser() {
  if (supabaseEnabled()) {
    await migrateFileUsersToSupabase();
    const existing = await findSupabaseUserByEmail(PREMIUM_SEED_EMAIL);
    if (existing) {
      if (!isPremiumUser(existing)) {
        await updateSupabaseUser(existing.id, { plan: "premium", billing_status: "active", paid_until: null });
      }
      return;
    }
    const passwordRecord = hashPassword(PREMIUM_SEED_PASSWORD);
    await insertSupabaseUser({
      id: crypto.randomUUID(),
      username: PREMIUM_SEED_USERNAME,
      email: PREMIUM_SEED_EMAIL,
      passwordSalt: passwordRecord.salt,
      passwordHash: passwordRecord.hash,
      recoverySecretHash: null,
      plan: "premium",
      billingStatus: "active",
      paidUntil: null,
      state: normalizeUserState({}),
      createdAt: new Date().toISOString()
    });
    return;
  }
  const data = readData();
  seedPremiumUserInFile(data);
  writeData(data);
}

async function migrateFileUsersToSupabase() {
  if (legacySupabaseMigrationDone) return;
  if (!fs.existsSync(DATA_FILE)) {
    legacySupabaseMigrationDone = true;
    return;
  }
  const data = readData();
  if (!Array.isArray(data.users) || data.users.length === 0) {
    legacySupabaseMigrationDone = true;
    return;
  }
  for (const fileUser of data.users) {
    const email = normalizeEmail(fileUser.email);
    if (!email) continue;
    if (!fileUser.passwordSalt || !fileUser.passwordHash) continue;
    const existing = await findSupabaseUserByEmail(email);
    if (existing) {
      const existingState = await getSupabaseUserState(existing.id);
      await putSupabaseUserState(existing.id, mergeUserState(existingState, fileUser.state || {}));
      continue;
    }
    await insertSupabaseUser({
      id: fileUser.id || crypto.randomUUID(),
      username: fileUser.username || email.split("@")[0] || "Reader",
      email,
      passwordSalt: fileUser.passwordSalt,
      passwordHash: fileUser.passwordHash,
      recoverySecretHash: fileUser.recoverySecretHash || null,
      plan: fileUser.plan || "free",
      billingStatus: fileUser.billingStatus || "none",
      paidUntil: fileUser.paidUntil || null,
      state: normalizeUserState(fileUser.state || {}),
      createdAt: fileUser.createdAt || new Date().toISOString()
    });
  }
  legacySupabaseMigrationDone = true;
}

function normalizeEmail(email) {
  return String(email || "").trim().toLowerCase();
}

function publicUser(user) {
  const plan = billingPlanFor(user?.plan);
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    plan: plan.id,
    billingStatus: user.billingStatus || "none",
    paidUntil: user.paidUntil || null,
    createdAt: user.createdAt,
    maxDevices: userMaxDevices(user)
  };
}

function billingPlanFor(planId) {
  const clean = String(planId || "free").trim().toLowerCase();
  if (clean === "premium") return BILLING_PLANS[DEFAULT_PREMIUM_PLAN_ID];
  return BILLING_PLANS[clean] || BILLING_PLANS.free;
}

function availableBillingPlans() {
  return [
    BILLING_PLANS[DEFAULT_PREMIUM_PLAN_ID],
    BILLING_PLANS.premium_unlimited
  ];
}

function isPremiumUser(user) {
  if (!user) return false;
  if (user.email === PREMIUM_SEED_EMAIL) return true;
  if (!billingPlanFor(user.plan).premium) return false;
  if ((user.billingStatus || "none") !== "active") return false;
  if (!user.paidUntil) return true;
  return Date.parse(user.paidUntil) > Date.now();
}

function userMaxDevices(user) {
  if (!user) return BILLING_PLANS.free.maxDevices;
  if (user.email === PREMIUM_SEED_EMAIL) return null;
  return billingPlanFor(user.plan).maxDevices;
}

function hashPassword(password, salt = crypto.randomBytes(16).toString("base64")) {
  const hash = crypto
    .pbkdf2Sync(String(password), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH, PASSWORD_DIGEST)
    .toString("base64");

  return { salt, hash };
}

function hashRecoverySecret(secret) {
  return crypto
    .createHash("sha256")
    .update(`novelapp-recovery-v1:${String(secret || "").trim().toLowerCase()}`)
    .digest("hex");
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

function countActiveFileSessions(data, userId) {
  const now = Date.now();
  data.sessions = data.sessions.filter((session) => session.expiresAt > now);
  return data.sessions.filter((session) => session.userId === userId).length;
}

async function assertCanCreateSession(user) {
  const limit = userMaxDevices(user);
  if (limit == null) return;
  const activeCount = supabaseEnabled()
    ? await countSupabaseActiveSessions(user.id)
    : countActiveFileSessions(readData(), user.id);
  if (activeCount >= limit) {
    const upgradeHint = limit < 3
      ? "Upgrade to the ₦1,000 plan for 3 devices or ₦4,000 for unlimited devices."
      : "Upgrade to the ₦4,000 unlimited device plan.";
    const error = new Error(`This account is already signed in on ${activeCount} device(s). ${upgradeHint}`);
    error.statusCode = 402;
    throw error;
  }
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
  ["renegade-immortal", "Renegade Immortal", "Er Gen", "Wuxiaworld", "Wang Lin walks a ruthless path through cultivation, revenge, and immortality.", "https://www.wuxiaworld.com/novel/renegade-immortal"],
  ["pursuit-of-truth", "Pursuit of Truth", "Er Gen", "Wuxiaworld", "Su Ming searches for identity and truth in a world of ancient power.", "https://www.wuxiaworld.com/novel/pursuit-of-the-truth"],
  ["a-will-eternal", "A Will Eternal", "Er Gen", "Wuxiaworld", "Bai Xiaochun wants to live forever and somehow keeps changing the world around him.", "https://www.wuxiaworld.com/novel/a-will-eternal"],
  ["lord-of-the-mysteries", "Lord of the Mysteries", "Cuttlefish That Loves Diving", "WebNovel", "A Victorian mystery of potions, gods, secret orders, and madness."],
  ["shadow-slave", "Shadow Slave", "Guiltythree", "WebNovel", "Sunny survives nightmare worlds while carrying a dangerous shadow bond."],
  ["omniscient-readers-viewpoint", "Omniscient Reader's Viewpoint", "Sing Shong", "WebNovel", "A reader becomes the only person who knows how the apocalypse story ends."],
  ["martial-peak", "Martial Peak", "Momo", "BoxNovel", "Yang Kai rises through martial worlds in a long cultivation journey."],
  ["coiling-dragon", "Coiling Dragon", "I Eat Tomatoes", "Wuxiaworld", "Linley trains from noble heir to world-shaking warrior and mage.", "https://www.wuxiaworld.com/novel/coiling-dragon-preview"],
  ["the-beginning-after-the-end", "The Beginning After The End", "TurtleMe", "Tapas", "A reincarnated king grows up in a magical world with new bonds and old burdens."],
  ["mother-of-learning", "Mother of Learning", "nobody103", "RoyalRoad", "A time-loop fantasy about a young mage uncovering a dangerous conspiracy.", "https://www.royalroad.com/fiction/21220/mother-of-learning"]
];

const KNOWN_MANGA = [
  ["solo-leveling", "Solo Leveling", "Chugong", "MangaDex", "The weakest hunter becomes the only player of a hidden leveling system.", "https://uploads.mangadex.org/covers/32c98f54-7aa7-4b53-94a1-1d6b1e8b0f0e/cover.jpg", "mangadex:32c98f54-7aa7-4b53-94a1-1d6b1e8b0f0e"],
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
  ["inception", "Inception", "Movie", "TMDB", "movie", "tmdb://movie/27205", "A thief enters dreams to plant an idea inside a corporate heir's mind.", "https://image.tmdb.org/t/p/w500/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg"],
  ["the-matrix", "The Matrix", "Movie", "TMDB", "movie", "tmdb://movie/603", "A hacker discovers the world he knows is a simulated reality.", "https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg"],
  ["spider-verse", "Spider-Man: Into the Spider-Verse", "Movie", "TMDB", "movie", "tmdb://movie/324857", "Miles Morales becomes Spider-Man and meets heroes from across the multiverse.", "https://image.tmdb.org/t/p/w500/iiZZdoQBEYBv6id8su7ImL0oCbD.jpg"],
  ["teen-titans", "Teen Titans", "Cartoon", "TMDB", "cartoon", "tmdb://tv/604", "Young heroes protect Jump City while growing as a team.", "https://image.tmdb.org/t/p/w500/8JfwXjP3iLjyfDfF0ECmS2rN0aA.jpg"],
  ["avatar-last-airbender", "Avatar: The Last Airbender", "Cartoon", "TMDB", "cartoon", "tmdb://tv/246", "A young Avatar must master the elements and stop a war.", "https://image.tmdb.org/t/p/w500/cHFZA8Tlv03nKTGXhLOYOLtqoSm.jpg"],
  ["adventure-time", "Adventure Time", "Cartoon", "TMDB", "cartoon", "tmdb://tv/15260", "Finn and Jake explore a strange magical land full of danger and jokes.", "https://image.tmdb.org/t/p/w500/qk3eQ8jW4opJ48gFWYUXWaMT4l.jpg"],
  ["spongebob-squarepants", "SpongeBob SquarePants", "Cartoon", "TMDB", "cartoon", "tmdb://tv/387", "SpongeBob works, plays, and creates chaos under the sea.", "https://image.tmdb.org/t/p/w500/8v5zQ9FJ6V4t4TYNDfLSk7R4hzG.jpg"],
  ["crash-landing-on-you", "Crash Landing on You", "K-Drama", "TMDB", "kdrama", "tmdb://tv/94796", "A South Korean heiress crash lands in North Korea and meets an officer.", "https://image.tmdb.org/t/p/w500/2u8I9AzgbLGGqE4JdW6uJQO0t5C.jpg"],
  ["squid-game", "Squid Game", "K-Drama", "TMDB", "kdrama", "tmdb://tv/93405", "Desperate players enter deadly games for a life-changing prize.", "https://image.tmdb.org/t/p/w500/dDlEmu3EZ0Pgg93K2SVNLCjCSvE.jpg"],
  ["true-beauty", "True Beauty", "K-Drama", "TMDB", "kdrama", "tmdb://tv/112888", "A high school student hides her insecurities behind makeup and finds connection.", "https://image.tmdb.org/t/p/w500/sld43SJArZqlnANJGBkZyQpXHHH.jpg"],
  ["queen-of-tears", "Queen of Tears", "K-Drama", "TMDB", "kdrama", "tmdb://tv/215720", "A married couple faces crisis, love, and family pressure at the top of a business empire.", "https://image.tmdb.org/t/p/w500/1uEwVlg4L7QjOglfaXr0iM2ZJ48.jpg"]
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
          detailUrl: item[5] || `novel://${item[0]}`,
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
          detailUrl: item[6] || `manga://${item[0]}`,
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

async function tmdbBestMatch(type, title) {
  const query = String(title || "").trim();
  if (!query) return null;
  const items = await tmdbItems(type, query, 1).catch(() => []);
  return items.find((item) => /^tmdb:\/\//.test(item.detailUrl)) || null;
}

async function wikidataTmdbMatch(type, title) {
  const query = String(title || "").trim();
  if (!query) return null;
  const normalizedType = normalizeContentType(type);
  const mediaType = normalizedType === "movies" ? "movie" : "tv";
  const property = mediaType === "movie" ? "P4947" : "P4983";
  const searchUrl = `https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${encodeURIComponent(query)}&language=en&format=json&limit=5&origin=*`;
  const search = await fetchWithTimeout(searchUrl, {
    headers: { "user-agent": "NovelApp/1.0 content resolver", accept: "application/json" }
  }, 9000).catch(() => null);
  const candidates = (search?.search || []).filter((item) => item.id);
  for (const candidate of candidates) {
    const entityUrl = `https://www.wikidata.org/wiki/Special:EntityData/${encodeURIComponent(candidate.id)}.json`;
    const entityPayload = await fetchWithTimeout(entityUrl, {
      headers: { "user-agent": "NovelApp/1.0 content resolver", accept: "application/json" }
    }, 9000).catch(() => null);
    const entity = entityPayload?.entities?.[candidate.id];
    const claim = entity?.claims?.[property]?.find((item) => item?.mainsnak?.datavalue?.value);
    const id = String(claim?.mainsnak?.datavalue?.value || "").trim();
    if (/^\d+$/.test(id)) {
      return contentItem({
        id: `wikidata_tmdb_${mediaType}_${id}`,
        title: candidate.label || query,
        subtitle: "Wikidata TMDB",
        detailUrl: `tmdb://${mediaType}/${id}`,
        sourceName: "Wikidata",
        kind: mediaType === "movie" ? "movie" : normalizedType,
        synopsis: candidate.description || ""
      });
    }
  }
  return null;
}

async function mangadexItems(query, page = 1) {
  const title = String(query || "").trim();
  const order = title ? "order[relevance]=desc" : "order[followedCount]=desc";
  const titleParam = title ? `&title=${encodeURIComponent(title)}` : "";
  const url = `https://api.mangadex.org/manga?limit=24&offset=${(page - 1) * 24}${titleParam}&includes[]=cover_art&availableTranslatedLanguage[]=en&${order}`;
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

async function mangadexChapters(detailUrl, page = 1) {
  const id = String(detailUrl || "").startsWith("mangadex:")
    ? String(detailUrl).replace("mangadex:", "")
    : "";
  if (!id) return [];
  const limit = 100;
  const offset = (Math.max(1, Number(page) || 1) - 1) * limit;
  const url = `https://api.mangadex.org/manga/${encodeURIComponent(id)}/feed?limit=${limit}&offset=${offset}&translatedLanguage[]=en&order[chapter]=asc&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica`;
  const payload = await fetchWithTimeout(url);
  return (payload.data || []).filter((item) => !item.attributes?.externalUrl).map((item, index) => {
    const attributes = item.attributes || {};
    const chapterNumber = Number.parseInt(String(attributes.chapter || ""), 10) || (offset + index + 1);
    const title = attributes.title
      ? `Chapter ${attributes.chapter || chapterNumber} - ${attributes.title}`
      : `Chapter ${attributes.chapter || chapterNumber}`;
    return {
      title,
      url: `mangadex-chapter://${item.id}`,
      chapterNumber
    };
  });
}

async function mangadexPages(chapterUrl) {
  const chapterId = String(chapterUrl || "").replace("mangadex-chapter://", "");
  if (!chapterId || chapterId === chapterUrl) return [];
  const payload = await fetchWithTimeout(`https://api.mangadex.org/at-home/server/${encodeURIComponent(chapterId)}`);
  const baseUrl = payload.baseUrl;
  const chapter = payload.chapter || {};
  const hash = chapter.hash || "";
  const pages = Array.isArray(chapter.dataSaver) && chapter.dataSaver.length
    ? chapter.dataSaver
    : chapter.data || [];
  if (!baseUrl || !hash || !pages.length) return [];
  return pages.map((page) => `${baseUrl}/data-saver/${hash}/${page}`);
}

async function contentHome(type, page = 1) {
  const normalizedType = normalizeContentType(type);
  if (normalizedType === "anime") {
    return anilistItems("", page).catch(() => fixtureItems("anime"));
  }
  if (normalizedType === "manga") {
    const live = await mangadexItems("", page).catch(() => []);
    return live.length ? live : fixtureItems("manga");
  }
  if (["kdrama", "cartoon", "movies"].includes(normalizedType)) {
    const tmdb = await tmdbItems(normalizedType, "", page).catch(() => []);
    return tmdb.length ? tmdb : fixtureItems(normalizedType);
  }
  const live = await swiftNovelScrapers.popularNovels(page).catch(() => []);
  return live.length ? live : fixtureItems("novels");
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
  const live = await swiftNovelScrapers.searchNovels(query, page).catch(() => []);
  return live.length ? live : fixtureItems("novels", query);
}

async function contentChapters(kind, detailUrl, title, sourceName) {
  if (normalizeContentType(kind) === "novels" || normalizeContentType(kind) === "novel") {
    const live = await swiftNovelScrapers.novelChapters({ detailUrl, sourceName }).catch(() => []);
    return live;
  }
  if (normalizeContentType(kind) === "manga") {
    const live = await mangadexChapters(detailUrl).catch(() => []);
    return live;
  }
  return syntheticChapters(kind, detailUrl, title);
}

async function contentChapterText(chapterUrl, title, sourceName) {
  const live = await swiftNovelScrapers.novelChapterText({ chapterUrl, sourceName }).catch(() => "");
  if (live && live.trim().length > 200) return live;
  if (String(chapterUrl || "").startsWith("novel-chapter://")) {
    return "Real chapter text is unavailable for this source right now. Try another result from Wuxiaworld or RoyalRoad.";
  }
  return "This chapter is unavailable from the provider right now. It may be locked, moved, or blocked by the source.";
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

function embedProviders(mediaType, id, season = "1", episode = "1") {
  const movie = mediaType === "movie";
  return [
    {
      provider: "VidLink Pro",
      url: movie ? `https://vidlink.pro/movie/${id}` : `https://vidlink.pro/tv/${id}/${season}/${episode}`
    },
    {
      provider: "AutoEmbed",
      url: movie ? `https://player.autoembed.cc/movie/${id}` : `https://player.autoembed.cc/tv/${id}/${season}/${episode}`
    },
    {
      provider: "VidSrc.me",
      url: movie
        ? `https://vidsrc.me/embed/movie?tmdb=${id}`
        : `https://vidsrc.me/embed/tv?tmdb=${id}&season=${season}&episode=${episode}`
    },
    {
      provider: "EmbedSu",
      url: movie ? `https://embed.su/embed/movie/${id}` : `https://embed.su/embed/tv/${id}/${season}/${episode}`
    },
    {
      provider: "VidSrc.cc",
      url: movie ? `https://vidsrc.cc/v2/embed/movie/${id}` : `https://vidsrc.cc/v2/embed/tv/${id}/${season}/${episode}`
    },
    {
      provider: "2Embed",
      url: movie ? `https://2embed.cc/embed/${id}` : `https://2embed.cc/embedtv/${id}&s=${season}&e=${episode}`
    }
  ];
}

function isDirectStreamUrl(url) {
  const clean = String(url || "").split("#")[0];
  return /\.(m3u8|mp4|mpd|webm)(\?|$)/i.test(clean) ||
    /\/(?:playlist|manifest|hls|dash)(?:[/?#]|$)/i.test(clean);
}

function firstDirectStreamFromPayload(payload) {
  const candidates = [];
  const visit = (value) => {
    if (!value) return;
    if (typeof value === "string") {
      if (isDirectStreamUrl(value)) candidates.push({ url: value });
      return;
    }
    if (Array.isArray(value)) {
      value.forEach(visit);
      return;
    }
    if (typeof value !== "object") return;
    const direct = value.url || value.file || value.src || value.playlist || value.streamUrl;
    if (typeof direct === "string" && isDirectStreamUrl(direct)) {
      candidates.push({
        url: direct,
        provider: value.provider?.name || value.provider?.id || value.provider || value.label || value.quality || "",
        headers: value.headers || value.proxyHeaders || null
      });
    }
    ["files", "sources", "streams", "data", "stream"].forEach((key) => visit(value[key]));
  };
  visit(payload);
  return candidates[0] || null;
}

function timeoutPromise(promise, timeoutMillis, label = "operation") {
  let timeoutId;
  const timeout = new Promise((_, reject) => {
    timeoutId = setTimeout(() => reject(new Error(`${label} timed out`)), timeoutMillis);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timeoutId));
}

function normalizeConsumetAnimeProvider(provider) {
  const raw = String(provider || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
  const alias = CONSUMET_ANIME_ALIASES[raw] || CONSUMET_ANIME_ALIASES[String(provider || "").toLowerCase()];
  const key = alias || raw;
  return CONSUMET_ANIME_PROVIDERS[key] ? key : "";
}

function consumetAnimeProvider(provider) {
  const key = normalizeConsumetAnimeProvider(provider);
  return key ? { key, ...CONSUMET_ANIME_PROVIDERS[key] } : null;
}

function consumetProviderInstance(providerKey) {
  const config = consumetAnimeProvider(providerKey);
  const ctor = config && consumetExtensions?.ANIME?.[config.className];
  return typeof ctor === "function" ? new ctor() : null;
}

function titleToText(title) {
  if (!title) return "";
  if (typeof title === "string") return title;
  if (Array.isArray(title)) return title.map((item) => Array.isArray(item) ? item[1] : item).filter(Boolean).join(" ");
  if (typeof title === "object") return title.english || title.userPreferred || title.romaji || title.native || Object.values(title).find(Boolean) || "";
  return String(title);
}

function normalizeMatchText(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function animeResultScore(query, result) {
  const q = normalizeMatchText(query);
  const t = normalizeMatchText(titleToText(result?.title));
  if (!q || !t) return 0;
  if (t === q) return 1000;
  if (t.startsWith(q)) return 850;
  if (t.includes(q)) return 650;
  const qWords = new Set(q.split(" ").filter(Boolean));
  const hits = t.split(" ").filter((word) => qWords.has(word)).length;
  return hits * 100 - Math.abs(t.length - q.length);
}

function safeResultsArray(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.results)) return payload.results;
  if (Array.isArray(payload?.data?.results)) return payload.data.results;
  if (Array.isArray(payload?.data)) return payload.data;
  if (Array.isArray(payload?.items)) return payload.items;
  return [];
}

function safeEpisodesArray(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.episodes)) return payload.episodes;
  if (Array.isArray(payload?.data?.episodes)) return payload.data.episodes;
  if (Array.isArray(payload?.data)) return payload.data;
  return [];
}

function consumetEpisodeNumber(episode, index) {
  const raw = episode?.number ?? episode?.episodeNumber ?? episode?.episode ?? episode?.episodeNum ?? episode?.ep;
  const parsed = Number.parseFloat(String(raw || ""));
  if (Number.isFinite(parsed) && parsed > 0) return Math.floor(parsed);
  const text = `${episode?.title || ""} ${episode?.id || ""}`;
  const match = /(?:episode|ep)[^0-9]{0,4}(\d+)/i.exec(text) || /(?:^|[^0-9])(\d+)(?:$|[^0-9])/i.exec(text);
  return Number.parseInt(match?.[1] || "", 10) || index + 1;
}

function consumetEpisodeMarker(providerKey, episodeId) {
  return `consumet://${providerKey}/${encodeURIComponent(String(episodeId || ""))}`;
}

function normalizeConsumetEpisodes(providerKey, episodes, maxEpisodes = 300) {
  return episodes
    .map((episode, index) => {
      const id = String(episode?.id || episode?.episodeId || episode?.url || "").trim();
      if (!id) return null;
      const episodeNumber = consumetEpisodeNumber(episode, index);
      return {
        episodeNumber,
        title: String(episode?.title || episode?.name || `Episode ${episodeNumber}`).trim(),
        url: consumetEpisodeMarker(providerKey, id),
        thumbnail: String(episode?.image || episode?.thumbnail || episode?.cover || "").trim()
      };
    })
    .filter(Boolean)
    .filter((episode, index, all) => all.findIndex((item) => item.url === episode.url) === index)
    .sort((a, b) => b.episodeNumber - a.episodeNumber)
    .slice(0, Math.max(1, maxEpisodes || 300));
}

async function packageConsumetAnimeEpisodes(providerKey, query, maxEpisodes) {
  const config = consumetAnimeProvider(providerKey);
  const provider = consumetProviderInstance(providerKey);
  if (!config || !provider?.search) return null;

  const searchPayload = await timeoutPromise(provider.search(query, 1), 16000, `${config.label} search`);
  const candidates = safeResultsArray(searchPayload)
    .filter((item) => item?.id)
    .sort((a, b) => animeResultScore(query, b) - animeResultScore(query, a))
    .slice(0, 5);

  for (const candidate of candidates) {
    const id = String(candidate.id || "").trim();
    if (!id) continue;
    const info = provider.fetchAnimeInfo
      ? await timeoutPromise(provider.fetchAnimeInfo(id), 18000, `${config.label} anime info`).catch(() => null)
      : candidate;
    const episodes = normalizeConsumetEpisodes(config.key, safeEpisodesArray(info), maxEpisodes);
    if (episodes.length) {
      return {
        provider: config.label,
        providerKey: config.key,
        title: titleToText(info?.title || candidate.title),
        episodes
      };
    }
  }

  return {
    provider: config.label,
    providerKey: config.key,
    title: "",
    episodes: []
  };
}

async function externalConsumetFetch(pathname, timeoutMillis = 16000) {
  if (!CONSUMET_BASE_URL || typeof fetch !== "function") return null;
  return fetchWithTimeout(`${CONSUMET_BASE_URL}${pathname}`, {
    headers: { accept: "application/json" }
  }, timeoutMillis).catch(() => null);
}

async function externalConsumetAnimeEpisodes(providerKey, query, maxEpisodes) {
  const config = consumetAnimeProvider(providerKey);
  if (!config || !CONSUMET_BASE_URL) return null;
  const encodedQuery = encodeURIComponent(query);
  const searchPayload = await firstNonNullAsync([
    () => externalConsumetFetch(`/anime/${config.path}/${encodedQuery}?page=1`),
    () => externalConsumetFetch(`/anime/${config.path}/${encodedQuery}`),
    () => externalConsumetFetch(`/anime/${config.path}?query=${encodedQuery}&page=1`)
  ]);
  const candidates = safeResultsArray(searchPayload)
    .filter((item) => item?.id)
    .sort((a, b) => animeResultScore(query, b) - animeResultScore(query, a))
    .slice(0, 5);
  for (const candidate of candidates) {
    const id = String(candidate.id || "").trim();
    const encodedId = encodeURIComponent(id);
    const info = await firstNonNullAsync([
      () => externalConsumetFetch(`/anime/${config.path}/info/${encodedId}`, 18000),
      () => externalConsumetFetch(`/anime/${config.path}/info?id=${encodedId}`, 18000)
    ]);
    const episodes = normalizeConsumetEpisodes(config.key, safeEpisodesArray(info || candidate), maxEpisodes);
    if (episodes.length) {
      return {
        provider: config.label,
        providerKey: config.key,
        title: titleToText(info?.title || candidate.title),
        episodes
      };
    }
  }
  return {
    provider: config.label,
    providerKey: config.key,
    title: "",
    episodes: []
  };
}

async function firstNonNullAsync(loaders) {
  for (const loader of loaders) {
    const result = await loader().catch(() => null);
    if (result) return result;
  }
  return null;
}

async function consumetAnimeEpisodes(provider, query, maxEpisodes = 300) {
  const config = consumetAnimeProvider(provider);
  if (!config) throw new Error("Unknown Consumet anime provider.");
  const local = await packageConsumetAnimeEpisodes(config.key, query, maxEpisodes).catch((error) => {
    console.warn(`[content] ${config.label} local episode lookup failed:`, error.message || error);
    return null;
  });
  if (local?.episodes?.length) return local;

  const external = await externalConsumetAnimeEpisodes(config.key, query, maxEpisodes).catch((error) => {
    console.warn(`[content] ${config.label} external episode lookup failed:`, error.message || error);
    return null;
  });
  if (external?.episodes?.length) return external;

  return {
    provider: config.label,
    providerKey: config.key,
    title: local?.title || external?.title || "",
    episodes: [],
    message: CONSUMET_BASE_URL || consumetExtensions?.ANIME
      ? "No episodes were returned by this anime provider."
      : "Consumet anime providers are not configured on this backend."
  };
}

async function packageConsumetAnimeStream(providerKey, episodeId) {
  const config = consumetAnimeProvider(providerKey);
  const provider = consumetProviderInstance(providerKey);
  if (!config || !provider?.fetchEpisodeSources) return null;
  const streamingServers = consumetExtensions?.StreamingServers || {};
  const subOrDub = consumetExtensions?.SubOrSub || {};
  const serverValues = [
    undefined,
    ...config.servers.map((server) => streamingServers[server] || String(server).toLowerCase())
  ];
  const subPrefs = [subOrDub.SUB, undefined, subOrDub.BOTH].filter((item, index, all) => all.indexOf(item) === index);

  for (const server of serverValues) {
    for (const subPref of subPrefs) {
      const args = [episodeId];
      if (server) args.push(server);
      if (server && subPref) args.push(subPref);
      const payload = await timeoutPromise(
        provider.fetchEpisodeSources(...args),
        18000,
        `${config.label} stream`
      ).catch(() => null);
      const stream = firstDirectStreamFromPayload(payload);
      if (stream?.url) {
        return {
          route: "direct",
          url: stream.url,
          provider: stream.provider || config.label,
          title: config.label,
          headers: stream.headers || null
        };
      }
    }
  }
  return null;
}

async function externalConsumetAnimeStream(providerKey, episodeId) {
  const config = consumetAnimeProvider(providerKey);
  if (!config || !CONSUMET_BASE_URL) return null;
  const encodedId = encodeURIComponent(episodeId);
  const payload = await firstNonNullAsync([
    () => externalConsumetFetch(`/anime/${config.path}/watch/${encodedId}`, 18000),
    () => externalConsumetFetch(`/anime/${config.path}/watch?episodeId=${encodedId}`, 18000)
  ]);
  const stream = firstDirectStreamFromPayload(payload);
  if (!stream?.url) return null;
  return {
    route: "direct",
    url: stream.url,
    provider: stream.provider || config.label,
    title: config.label,
    headers: stream.headers || null
  };
}

async function consumetAnimeStreamRoute(provider, episodeId) {
  const config = consumetAnimeProvider(provider);
  if (!config) throw new Error("Unknown Consumet anime provider.");
  const local = await packageConsumetAnimeStream(config.key, episodeId).catch((error) => {
    console.warn(`[content] ${config.label} local stream lookup failed:`, error.message || error);
    return null;
  });
  if (local?.url && isDirectStreamUrl(local.url)) return local;
  const external = await externalConsumetAnimeStream(config.key, episodeId).catch((error) => {
    console.warn(`[content] ${config.label} external stream lookup failed:`, error.message || error);
    return null;
  });
  if (external?.url && isDirectStreamUrl(external.url)) return external;
  return {
    route: "unavailable",
    url: "",
    provider: config.label,
    title: "Unavailable",
    message: "This anime server did not return a direct playable stream."
  };
}

async function vidlinkDirectRoute(mediaType, id, season = "1", episode = "1") {
  if (!VIDLINK_RESOLVER_BASE_URL || typeof fetch !== "function") return null;
  const params = new URLSearchParams({ id: String(id) });
  if (mediaType !== "movie") {
    params.set("s", String(season || "1"));
    params.set("e", String(episode || "1"));
  }
  const payload = await fetchWithTimeout(`${VIDLINK_RESOLVER_BASE_URL}/api?${params.toString()}`, {
    headers: { accept: "application/json" }
  }, 15000).catch(() => null);
  const stream = firstDirectStreamFromPayload(payload);
  if (!stream?.url) return null;
  return {
    route: "direct",
    url: stream.url,
    provider: "VidLink Direct",
    title: "VidLink Direct",
    headers: stream.headers || null
  };
}

async function cineProviderRoute(mediaType, id, season = "1", episode = "1") {
  if (!CINEPRO_BASE_URL || typeof fetch !== "function") return null;
  const endpoint = mediaType === "movie"
    ? `${CINEPRO_BASE_URL}/movie/${encodeURIComponent(id)}`
    : `${CINEPRO_BASE_URL}/tv/${encodeURIComponent(id)}?s=${encodeURIComponent(season || "1")}&e=${encodeURIComponent(episode || "1")}`;
  const payload = await fetchWithTimeout(endpoint, {
    headers: { accept: "application/json" }
  }, 20000).catch(() => null);
  const stream = firstDirectStreamFromPayload(payload);
  if (!stream?.url) return null;
  return {
    route: "direct",
    url: stream.url,
    provider: stream.provider || "CinePro",
    title: "CinePro",
    headers: stream.headers || null
  };
}

async function providerStreamRoute(mediaType, id, season, episode, provider) {
  const normalizedProvider = String(provider || "vidlink").toLowerCase();
  const normalizedType = mediaType === "movie" ? "movie" : "tv";
  const routes = [];
  if (normalizedProvider === "vidlink" || normalizedProvider === "all") {
    const route = await vidlinkDirectRoute(normalizedType, id, season, episode);
    if (route) routes.push(route);
  }
  if (normalizedProvider === "cinepro" || normalizedProvider === "cinehub" || normalizedProvider === "all") {
    const route = await cineProviderRoute(normalizedType, id, season, episode);
    if (route) routes.push(route);
  }
  if (normalizedProvider === "omss" || normalizedProvider === "all") {
    const route = await omssRoute(normalizedType, id, season, episode, "");
    if (route && route.route === "direct" && isDirectStreamUrl(route.url)) routes.push(route);
  }
  return routes[0] || {
    route: "unavailable",
    url: "",
    provider: provider || "",
    title: "Unavailable",
    message: "This server did not return a direct playable stream."
  };
}

async function omssRoute(mediaType, id, season, episode, title) {
  if (!OMSS_BASE_URL || typeof fetch !== "function") return null;

  const endpoint = mediaType === "movie"
    ? `${OMSS_BASE_URL}/v1/movies/${encodeURIComponent(id)}?platform=web`
    : `${OMSS_BASE_URL}/v1/tv/${encodeURIComponent(id)}/seasons/${encodeURIComponent(season)}/episodes/${encodeURIComponent(episode)}?platform=web`;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10000);

  try {
    const result = await fetch(endpoint, {
      headers: { accept: "application/json" },
      signal: controller.signal
    });
    if (!result.ok) return null;
    const body = await result.json();
    const source = Array.isArray(body.sources)
      ? body.sources.find((item) => item && item.url && item.streamable !== false) || body.sources.find((item) => item && item.url)
      : null;
    if (!source) return null;
    const sourceUrl = String(source.url);
    const direct = /\.(m3u8|mp4|mpd)(\?|$)/i.test(sourceUrl) || ["hls", "dash", "mp4"].includes(String(source.type || "").toLowerCase());
    return {
      route: direct ? "direct" : "embed",
      url: sourceUrl,
      provider: source.provider?.name || source.provider?.id || "OMSS",
      title: title || "Watch",
      diagnostics: Array.isArray(body.diagnostics) ? body.diagnostics : []
    };
  } catch (error) {
    console.warn("[content] OMSS provider failed:", error.message || error);
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

async function watchRoute(kind, title, detailUrl) {
  const routes = await watchRoutes(kind, title, detailUrl);
  return routes[0] || {
    route: "unavailable",
    url: "",
    provider: "",
    title: title || "Unavailable",
    message: "No playable provider was available for this title."
  };
}

async function watchRoutes(kind, title, detailUrl) {
  const normalizedKind = normalizeContentType(kind);
  let resolvedDetailUrl = String(detailUrl || "");
  if (/^anilist:/i.test(resolvedDetailUrl) || !resolvedDetailUrl) {
    const tmdbType = normalizedKind === "movies" ? "movies" : normalizedKind;
    const match = await tmdbBestMatch(tmdbType, title)
      || await wikidataTmdbMatch(tmdbType, title);
    if (match?.detailUrl) resolvedDetailUrl = match.detailUrl;
  }

  const tmdbMatch = /^tmdb:\/\/([^/]+)\/(\d+)/.exec(resolvedDetailUrl);
  if (tmdbMatch) {
    const mediaType = tmdbMatch[1] === "movie" || normalizedKind === "movies" ? "movie" : "tv";
    const id = tmdbMatch[2];
    const routes = [];
    const omss = await omssRoute(mediaType, id, "1", "1", title);
    if (omss) routes.push(omss);
    routes.push(...embedProviders(mediaType, id).map((provider) => ({
      route: "embed",
      url: provider.url,
      provider: provider.provider,
      title: title || "Watch"
    })));
    return routes;
  }
  if (/^https?:\/\//i.test(resolvedDetailUrl)) {
    const direct = /\.(m3u8|mp4|mpd)(\?|$)/i.test(resolvedDetailUrl);
    return [{
      route: direct ? "direct" : "embed",
      url: resolvedDetailUrl,
      provider: direct ? "Direct" : "Web",
      title: title || "Watch"
    }];
  }
  return [{
    route: "unavailable",
    url: "",
    provider: "",
    title: title || "Unavailable",
    message: "No playable provider was available. Add TMDB credentials or OMSS on the backend for this title."
  }];
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
    if (request.method === "GET" && pathname === "/api/content/providers") {
      return sendApiData(response, 200, {
        omss: {
          enabled: Boolean(OMSS_BASE_URL)
        },
        vidlink: {
          enabled: Boolean(VIDLINK_RESOLVER_BASE_URL)
        },
        cinepro: {
          enabled: Boolean(CINEPRO_BASE_URL)
        },
        consumetAnime: {
          enabled: Boolean(consumetExtensions?.ANIME || CONSUMET_BASE_URL),
          localPackage: Boolean(consumetExtensions?.ANIME),
          externalBaseUrl: Boolean(CONSUMET_BASE_URL),
          providers: Object.entries(CONSUMET_ANIME_PROVIDERS).map(([key, value]) => ({
            key,
            label: value.label
          }))
        },
        directServers: ["VidLink Direct", "CinePro", "OMSS"],
        embeds: embedProviders("movie", "{tmdb_id}").map((provider) => provider.provider)
      });
    }
    if (request.method === "GET" && pathname === "/api/content/anime/episodes") {
      const provider = String(url.searchParams.get("provider") || "hianime").trim();
      const query = String(url.searchParams.get("q") || "").trim();
      if (!query) return sendApiError(response, 400, "Anime title query is required.");
      const limit = Math.max(1, Math.min(500, Number(url.searchParams.get("limit") || 300) || 300));
      return sendApiData(response, 200, await consumetAnimeEpisodes(provider, query, limit));
    }
    if (request.method === "GET" && pathname === "/api/content/anime/stream") {
      const provider = String(url.searchParams.get("provider") || "hianime").trim();
      const episodeId = String(url.searchParams.get("episodeId") || "").trim();
      if (!episodeId) return sendApiError(response, 400, "Consumet episode id is required.");
      return sendApiData(response, 200, await consumetAnimeStreamRoute(provider, episodeId));
    }
    if (request.method === "GET" && pathname === "/api/content/stream") {
      const mediaType = String(url.searchParams.get("type") || "movie").toLowerCase() === "movie" ? "movie" : "tv";
      const id = String(url.searchParams.get("id") || "").trim();
      if (!id) return sendApiError(response, 400, "TMDB id is required.");
      const season = String(url.searchParams.get("season") || "1");
      const episode = String(url.searchParams.get("episode") || "1");
      const provider = String(url.searchParams.get("provider") || "vidlink");
      return sendApiData(response, 200, await providerStreamRoute(mediaType, id, season, episode, provider));
    }
    if (request.method === "POST" && pathname === "/api/content/chapters") {
      const body = await readBody(request);
      return sendApiData(response, 200, {
        chapters: await contentChapters(body.kind, body.detailUrl, body.title, body.sourceName)
      });
    }
    if (request.method === "POST" && pathname === "/api/content/chapter-text") {
      const body = await readBody(request);
      return sendApiData(response, 200, {
        text: await contentChapterText(body.chapterUrl, body.title, body.sourceName)
      });
    }
    if (request.method === "POST" && pathname === "/api/content/manga-pages") {
      const body = await readBody(request);
      const live = await mangadexPages(body.chapterUrl).catch(() => []);
      return sendApiData(response, 200, { pages: live });
    }
    if (request.method === "POST" && pathname === "/api/content/watch-route") {
      const body = await readBody(request);
      return sendApiData(response, 200, await watchRoute(body.kind, body.title, body.detailUrl));
    }
    if (request.method === "POST" && pathname === "/api/content/watch-routes") {
      const body = await readBody(request);
      return sendApiData(response, 200, { routes: await watchRoutes(body.kind, body.title, body.detailUrl) });
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
  const recoverySecret = String(body.recoverySecret || body.secretKey || "").trim();

  if (username.length < 2) return sendError(response, 400, "Username must be at least 2 characters.");
  if (!email.includes("@") || !email.includes(".")) return sendError(response, 400, "Enter a valid email.");
  if (password.length < 6) return sendError(response, 400, "Password must be at least 6 characters.");
  if (recoverySecret.length < 10) return sendError(response, 400, "Recovery secret must be at least 10 characters.");
  const recoverySecretHash = hashRecoverySecret(recoverySecret);

  await ensurePremiumSeedUser();

  if (supabaseEnabled()) {
    if (await findSupabaseUserByEmail(email)) {
      return sendError(response, 409, "An account with this email already exists.");
    }
    if (await findSupabaseUserByRecoveryHash(recoverySecretHash)) {
      return sendError(response, 409, "That recovery secret is already used. Choose another one.");
    }
    const passwordRecord = hashPassword(password);
    const user = await insertSupabaseUser({
      id: crypto.randomUUID(),
      username,
      email,
      passwordSalt: passwordRecord.salt,
      passwordHash: passwordRecord.hash,
      recoverySecretHash,
      plan: email === PREMIUM_SEED_EMAIL ? "premium" : "free",
      billingStatus: email === PREMIUM_SEED_EMAIL ? "active" : "none",
      paidUntil: null,
      state: normalizeUserState({}),
      createdAt: new Date().toISOString()
    });
    const token = await createSupabaseSession(user.id);
    return sendJson(response, 201, { token, user: publicUser(user) });
  }

  const data = readData();
  seedPremiumUserInFile(data);
  if (data.users.some((user) => user.email === email)) {
    return sendError(response, 409, "An account with this email already exists.");
  }
  if (data.users.some((user) => user.recoverySecretHash === recoverySecretHash)) {
    return sendError(response, 409, "That recovery secret is already used. Choose another one.");
  }

  const passwordRecord = hashPassword(password);
  const user = {
    id: crypto.randomUUID(),
    username,
    email,
    passwordSalt: passwordRecord.salt,
    passwordHash: passwordRecord.hash,
    recoverySecretHash,
    plan: email === PREMIUM_SEED_EMAIL ? "premium" : "free",
    billingStatus: email === PREMIUM_SEED_EMAIL ? "active" : "none",
    paidUntil: null,
    state: normalizeUserState({}),
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
  await ensurePremiumSeedUser();

  if (supabaseEnabled()) {
    const user = await findSupabaseUserByEmail(email);
    if (!user || !passwordMatches(password, user.passwordSalt, user.passwordHash)) {
      return sendError(response, 401, "Email or password is incorrect.");
    }
    try {
      await assertCanCreateSession(user);
    } catch (error) {
      return sendError(response, error.statusCode || 403, error.message);
    }
    const token = await createSupabaseSession(user.id);
    return sendJson(response, 200, { token, user: publicUser(user) });
  }

  const data = readData();
  seedPremiumUserInFile(data);
  const user = data.users.find((item) => item.email === email);

  if (!user || !passwordMatches(password, user.passwordSalt, user.passwordHash)) {
    return sendError(response, 401, "Email or password is incorrect.");
  }
  try {
    await assertCanCreateSession(user);
  } catch (error) {
    return sendError(response, error.statusCode || 403, error.message);
  }

  const token = createSession(data, user.id);
  writeData(data);

  return sendJson(response, 200, { token, user: publicUser(user) });
}

async function handleMe(request, response) {
  await ensurePremiumSeedUser();
  if (supabaseEnabled()) {
    const user = await findSupabaseSessionUser(getBearerToken(request));
    if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
    return sendJson(response, 200, { user: publicUser(user) });
  }
  const data = readData();
  seedPremiumUserInFile(data);
  const user = findSessionUser(data, getBearerToken(request));

  if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
  return sendJson(response, 200, { user: publicUser(user) });
}

async function handleLogout(request, response) {
  if (supabaseEnabled()) {
    await removeSupabaseSession(getBearerToken(request));
    return sendJson(response, 200, { ok: true });
  }
  const data = readData();
  removeSession(data, getBearerToken(request));
  writeData(data);
  return sendJson(response, 200, { ok: true });
}

async function handleRecoverAccount(request, response) {
  const body = await readBody(request);
  const recoverySecret = String(body.recoverySecret || body.secretKey || "").trim();
  if (recoverySecret.length < 10) return sendError(response, 400, "Recovery secret must be at least 10 characters.");
  await ensurePremiumSeedUser();
  const secretHash = hashRecoverySecret(recoverySecret);
  const user = supabaseEnabled()
    ? await findSupabaseUserByRecoveryHash(secretHash)
    : (() => {
        const data = readData();
        seedPremiumUserInFile(data);
        writeData(data);
        return data.users.find((item) => item.recoverySecretHash === secretHash);
      })();

  if (!user) return sendError(response, 404, "No account matched that recovery secret.");
  try {
    await assertCanCreateSession(user);
  } catch (error) {
    return sendError(response, error.statusCode || 403, error.message);
  }
  const token = supabaseEnabled()
    ? await createSupabaseSession(user.id)
    : (() => {
        const data = readData();
        seedPremiumUserInFile(data);
        const active = data.users.find((item) => item.id === user.id);
        const nextToken = createSession(data, active.id);
        writeData(data);
        return nextToken;
      })();
  return sendJson(response, 200, {
    token,
    user: publicUser(user),
    message: "Account recovered. You are signed in; set a new password if you forgot the old one."
  });
}

async function handleResetPassword(request, response) {
  const token = getBearerToken(request);
  const body = await readBody(request);
  const password = String(body.password || "");
  if (password.length < 6) return sendError(response, 400, "Password must be at least 6 characters.");
  const passwordRecord = hashPassword(password);
  if (supabaseEnabled()) {
    const user = await findSupabaseSessionUser(token);
    if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
    const updated = await updateSupabaseUser(user.id, {
      password_salt: passwordRecord.salt,
      password_hash: passwordRecord.hash
    });
    return sendJson(response, 200, { user: publicUser(updated) });
  }
  const data = readData();
  const user = findSessionUser(data, token);
  if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
  user.passwordSalt = passwordRecord.salt;
  user.passwordHash = passwordRecord.hash;
  writeData(data);
  return sendJson(response, 200, { user: publicUser(user) });
}

async function requireApiUser(request, response) {
  const token = getBearerToken(request);
  const user = supabaseEnabled()
    ? await findSupabaseSessionUser(token)
    : findSessionUser(readData(), token);
  if (!user) {
    sendError(response, 401, "Session expired. Please sign in again.");
    return null;
  }
  return user;
}

function subscriptionPayload(user) {
  const plan = billingPlanFor(user?.plan);
  return {
    user: publicUser(user),
    premium: isPremiumUser(user),
    currentPlan: plan.id,
    monthlyFee: BILLING_PLANS[DEFAULT_PREMIUM_PLAN_ID].amount,
    currency: "NGN",
    maxDevices: userMaxDevices(user),
    plans: availableBillingPlans(),
    freePreview: {
      episodicFraction: EPISODIC_FREE_FRACTION,
      movieMs: MOVIE_FREE_PREVIEW_MS
    }
  };
}

async function handleBillingStatus(request, response) {
  const user = await requireApiUser(request, response);
  if (!user) return;
  return sendJson(response, 200, subscriptionPayload(user));
}

async function flutterwaveRequest(pathname, { method = "GET", body } = {}) {
  if (!FLUTTERWAVE_SECRET_KEY) {
    throw new Error("Flutterwave is not configured on the server.");
  }
  const response = await fetch(`https://api.flutterwave.com/v3/${pathname.replace(/^\/+/, "")}`, {
    method,
    headers: {
      authorization: `Bearer ${FLUTTERWAVE_SECRET_KEY}`,
      accept: "application/json",
      "content-type": "application/json"
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(payload?.message || `Flutterwave request failed (${response.status}).`);
  }
  return payload;
}

function addOneMonth(date = new Date()) {
  const next = new Date(date.getTime());
  next.setMonth(next.getMonth() + 1);
  return next.toISOString();
}

async function recordBillingEvent({ userId, txRef, transactionId, status, amount, currency, raw }) {
  const event = {
    user_id: userId || null,
    provider: "flutterwave",
    tx_ref: txRef || null,
    transaction_id: transactionId ? String(transactionId) : null,
    status: status || "unknown",
    amount: Number(amount || 0),
    currency: currency || "NGN",
    raw: raw || {}
  };
  if (supabaseEnabled()) {
    await supabaseRequest("novel_billing_events?on_conflict=tx_ref", {
      method: "POST",
      prefer: "resolution=merge-duplicates",
      body: event
    });
    return;
  }
  const data = readData();
  data.billingEvents = Array.isArray(data.billingEvents) ? data.billingEvents : [];
  const existingIndex = data.billingEvents.findIndex((item) => item.txRef && item.txRef === txRef);
  const savedEvent = {
    id: existingIndex >= 0 ? data.billingEvents[existingIndex].id : crypto.randomUUID(),
    userId: userId || null,
    txRef: txRef || null,
    transactionId: transactionId ? String(transactionId) : null,
    status: status || "unknown",
    amount: Number(amount || 0),
    currency: currency || "NGN",
    raw: raw || {},
    createdAt: existingIndex >= 0 ? data.billingEvents[existingIndex].createdAt : new Date().toISOString()
  };
  if (existingIndex >= 0) data.billingEvents[existingIndex] = savedEvent;
  else data.billingEvents.push(savedEvent);
  writeData(data);
}

async function markUserPremium(userId, payment, planId = DEFAULT_PREMIUM_PLAN_ID) {
  const plan = billingPlanFor(planId);
  if (!plan.premium) throw new Error("Invalid paid plan.");
  const paidUntil = addOneMonth();
  if (supabaseEnabled()) {
    const user = await updateSupabaseUser(userId, {
      plan: plan.id,
      billing_status: "active",
      paid_until: paidUntil
    });
    await recordBillingEvent({
      userId,
      txRef: payment.txRef,
      transactionId: payment.transactionId,
      status: payment.status,
      amount: payment.amount,
      currency: payment.currency,
      raw: payment.raw
    });
    return user;
  }
  const data = readData();
  const user = data.users.find((item) => item.id === userId);
  if (!user) throw new Error("Account not found for this payment.");
  user.plan = plan.id;
  user.billingStatus = "active";
  user.paidUntil = paidUntil;
  data.billingEvents = Array.isArray(data.billingEvents) ? data.billingEvents : [];
  data.billingEvents.push({
    id: crypto.randomUUID(),
    userId,
    txRef: payment.txRef || null,
    transactionId: payment.transactionId ? String(payment.transactionId) : null,
    status: payment.status,
    amount: Number(payment.amount || 0),
    currency: payment.currency || "NGN",
    raw: payment.raw || {},
    createdAt: new Date().toISOString()
  });
  writeData(data);
  return user;
}

function extractCheckoutUserId(txRef) {
  const value = String(txRef || "");
  return /^novelapp-sub-[a-z0-9_]+-([0-9a-f-]{36})-/i.exec(value)?.[1]
    || /^novelapp-sub-([0-9a-f-]{36})-/i.exec(value)?.[1]
    || null;
}

function extractCheckoutPlanId(txRef) {
  const value = String(txRef || "");
  return /^novelapp-sub-([a-z0-9_]+)-[0-9a-f-]{36}-/i.exec(value)?.[1]
    || DEFAULT_PREMIUM_PLAN_ID;
}

async function handleBillingCheckout(request, response) {
  const user = await requireApiUser(request, response);
  if (!user) return;
  const body = request.method === "POST" ? await readBody(request).catch(() => ({})) : {};
  const requestedPlan = billingPlanFor(body.planId || body.plan || DEFAULT_PREMIUM_PLAN_ID);
  if (!requestedPlan.premium) return sendError(response, 400, "Choose a paid plan.");
  const currentPlan = billingPlanFor(user.plan);
  if (isPremiumUser(user) && currentPlan.amount >= requestedPlan.amount) {
    return sendJson(response, 200, { ...subscriptionPayload(user), alreadyPremium: true });
  }
  const txRef = `novelapp-sub-${requestedPlan.id}-${user.id}-${Date.now()}-${crypto.randomBytes(4).toString("hex")}`;
  const payload = await flutterwaveRequest("payments", {
    method: "POST",
    body: {
      tx_ref: txRef,
      amount: requestedPlan.amount,
      currency: requestedPlan.currency,
      redirect_url: `${PUBLIC_APP_URL}/billing-return.html`,
      customer: {
        email: user.email,
        name: user.username
      },
      customizations: {
        title: requestedPlan.label,
        description: requestedPlan.description
      },
      meta: {
        user_id: user.id,
        plan: requestedPlan.id
      }
    }
  });
  const link = payload?.data?.link;
  if (!link) throw new Error("Flutterwave did not return a checkout link.");
  return sendJson(response, 200, {
    link,
    txRef,
    amount: requestedPlan.amount,
    currency: requestedPlan.currency,
    plan: requestedPlan
  });
}

async function verifyFlutterwavePayment({ transactionId, txRef }) {
  const payload = transactionId
    ? await flutterwaveRequest(`transactions/${encodeURIComponent(transactionId)}/verify`)
    : await flutterwaveRequest(`transactions/verify_by_reference?tx_ref=${encodeURIComponent(txRef)}`);
  const data = payload?.data || {};
  const amount = Number(data.amount || 0);
  const currency = String(data.currency || "").toUpperCase();
  const status = String(data.status || "").toLowerCase();
  const resolvedTxRef = data.tx_ref || txRef;
  const planId = data.meta?.plan || extractCheckoutPlanId(resolvedTxRef);
  const plan = billingPlanFor(planId);
  if (!plan.premium || status !== "successful" || currency !== plan.currency || amount < plan.amount) {
    await recordBillingEvent({
      userId: extractCheckoutUserId(resolvedTxRef),
      txRef: resolvedTxRef,
      transactionId: data.id || transactionId,
      status: data.status || status,
      amount,
      currency,
      raw: payload
    });
    throw new Error("Payment was not successful for the selected subscription.");
  }
  const userId = data.meta?.user_id || extractCheckoutUserId(resolvedTxRef);
  if (!userId) throw new Error("Payment did not include an account id.");
  return markUserPremium(userId, {
    txRef: resolvedTxRef,
    transactionId: data.id || transactionId,
    status: data.status || status,
    amount,
    currency,
    raw: payload
  }, plan.id);
}

async function handleBillingVerify(request, response) {
  const body = await readBody(request);
  const transactionId = body.transactionId || body.transaction_id || body.id;
  const txRef = body.txRef || body.tx_ref;
  if (!transactionId && !txRef) return sendError(response, 400, "Payment reference is required.");
  const user = await verifyFlutterwavePayment({ transactionId, txRef });
  return sendJson(response, 200, subscriptionPayload(user));
}

async function handleFlutterwaveWebhook(request, response) {
  if (FLUTTERWAVE_SECRET_HASH) {
    const signature = String(request.headers["verif-hash"] || "");
    if (signature !== FLUTTERWAVE_SECRET_HASH) {
      return sendError(response, 401, "Invalid webhook signature.");
    }
  }
  const payload = await readBody(request);
  const data = payload?.data || payload;
  const transactionId = data?.id || data?.transaction_id;
  const txRef = data?.tx_ref;
  if (transactionId || txRef) {
    await verifyFlutterwavePayment({ transactionId, txRef }).catch(async (error) => {
      await recordBillingEvent({
        userId: data?.meta?.user_id || extractCheckoutUserId(txRef),
        txRef,
        transactionId,
        status: data?.status || "failed",
        amount: data?.amount,
        currency: data?.currency,
        raw: { payload, error: error.message }
      });
    });
  }
  return sendJson(response, 200, { received: true });
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
    searchHistory: Array.isArray(state.searchHistory) ? state.searchHistory.slice(0, 60) : [],
    updatedAt
  };
}

function itemTime(item, fallback = 0) {
  if (!item || typeof item !== "object") return fallback;
  const raw = item.updatedAt ?? item.addedAt ?? item.timestamp ?? fallback;
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  if (typeof raw === "string") return Date.parse(raw) || fallback;
  return fallback;
}

function mergeByKey(existing, incoming, keyFn, limit, fallbackTime = Date.now()) {
  const map = new Map();
  for (const item of [...existing, ...incoming]) {
    if (!item || typeof item !== "object") continue;
    const key = keyFn(item);
    if (!key) continue;
    const previous = map.get(key);
    if (!previous || itemTime(item, fallbackTime) >= itemTime(previous, 0)) {
      map.set(key, item);
    }
  }
  return [...map.values()]
    .sort((a, b) => itemTime(b, 0) - itemTime(a, 0))
    .slice(0, limit);
}

function mergeUserState(existingState, incomingState) {
  const existing = normalizeUserState(existingState);
  const incoming = normalizeUserState(incomingState);
  const now = Date.now();
  return {
    favorites: mergeByKey(
      existing.favorites,
      incoming.favorites,
      (item) => String(item.id || item.detailPageUrl || item.detailUrl || "").trim(),
      250,
      now
    ),
    readHistory: mergeByKey(
      existing.readHistory,
      incoming.readHistory,
      (item) => String(item.chapterUrl || `${item.parentId || ""}:${item.chapterTitle || ""}`).trim(),
      100,
      now
    ),
    watchHistory: mergeByKey(
      existing.watchHistory,
      incoming.watchHistory,
      (item) => String(item.streamUrl || `${item.parentId || ""}:${item.episodeNumber || ""}`).trim(),
      100,
      now
    ),
    searchHistory: mergeByKey(
      existing.searchHistory,
      incoming.searchHistory,
      (item) => `${String(item.tab || item.kind || "").trim().toLowerCase()}:${String(item.query || "").trim().toLowerCase()}`,
      60,
      now
    ),
    updatedAt: now
  };
}

async function handleGetUserState(request, response) {
  if (supabaseEnabled()) {
    const user = await findSupabaseSessionUser(getBearerToken(request));
    if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
    const state = await getSupabaseUserState(user.id);
    return sendJson(response, 200, { user: publicUser(user), state });
  }
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
  if (supabaseEnabled()) {
    const user = await findSupabaseSessionUser(getBearerToken(request));
    if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
    const body = await readBody(request);
    const existing = await getSupabaseUserState(user.id);
    const merged = mergeUserState(existing, body.state || body);
    const state = await putSupabaseUserState(user.id, merged);
    return sendJson(response, 200, { user: publicUser(user), state });
  }
  const data = readData();
  const user = findSessionUser(data, getBearerToken(request));

  if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
  const body = await readBody(request);
  user.state = mergeUserState(user.state, body.state || body);
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
      return await handleMe(request, response);
    }
    if (request.method === "POST" && pathname === "/api/auth/logout") {
      return await handleLogout(request, response);
    }
    if (request.method === "POST" && pathname === "/api/auth/recover") {
      return await handleRecoverAccount(request, response);
    }
    if (request.method === "POST" && pathname === "/api/auth/reset-password") {
      return await handleResetPassword(request, response);
    }
    if (request.method === "GET" && pathname === "/api/billing/status") {
      return await handleBillingStatus(request, response);
    }
    if (request.method === "POST" && pathname === "/api/billing/checkout") {
      return await handleBillingCheckout(request, response);
    }
    if (request.method === "POST" && pathname === "/api/billing/verify") {
      return await handleBillingVerify(request, response);
    }
    if (request.method === "POST" && pathname === "/api/billing/flutterwave-webhook") {
      return await handleFlutterwaveWebhook(request, response);
    }
    if (request.method === "GET" && pathname === "/api/user/state") {
      return await handleGetUserState(request, response);
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

  // Kokoro manifest — mobile clients download the ONNX model once and cache it on device.
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
ensurePremiumSeedUser()
  .catch((error) => {
    console.warn(`Premium seed account could not be prepared: ${error.message || error}`);
  })
  .finally(() => {
    server.listen(PORT, () => {
      console.log(`NovelApp server listening on ${PORT}`);
    });
  });
