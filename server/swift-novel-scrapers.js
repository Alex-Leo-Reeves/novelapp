const USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1";

function absoluteUrl(base, href) {
  try {
    return new URL(href || "", base).toString();
  } catch {
    return "";
  }
}

async function fetchText(url, options = {}, timeoutMillis = 12000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMillis);
  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
      headers: {
        "user-agent": USER_AGENT,
        accept: "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "accept-language": "en-US,en;q=0.9",
        ...(options.headers || {})
      }
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.text();
  } finally {
    clearTimeout(timeout);
  }
}

async function fetchJson(url, options = {}, timeoutMillis = 12000) {
  const text = await fetchText(url, {
    ...options,
    headers: {
      accept: "application/json,text/plain,*/*",
      ...(options.headers || {})
    }
  }, timeoutMillis);
  return JSON.parse(text);
}

function decodeHtml(value) {
  return String(value || "")
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCharCode(parseInt(code, 16)))
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, "\"")
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/\s+/g, " ")
    .trim();
}

function htmlToText(html) {
  return decodeHtml(
    String(html || "")
      .replace(/<script[\s\S]*?<\/script>/gi, " ")
      .replace(/<style[\s\S]*?<\/style>/gi, " ")
      .replace(/<(?:p|br|div|h[1-6]|li|blockquote)\b[^>]*>/gi, "\n")
      .replace(/<[^>]+>/g, " ")
      .replace(/[ \t]+\n/g, "\n")
      .replace(/\n{3,}/g, "\n\n")
  );
}

function attr(html, name) {
  const match = new RegExp(`${name}=["']([^"']+)["']`, "i").exec(String(html || ""));
  return match ? decodeHtml(match[1]) : "";
}

function blocked(html) {
  const text = String(html || "").toLowerCase();
  return text.includes("cloudflare") && text.includes("checking your browser")
    || text.includes("access denied")
    || text.includes("attention required");
}

function chapterNumberFrom(value, fallback) {
  const text = String(value || "");
  const match = /\/chapter\/\d+\/(\d+)/i.exec(text)
    || /(?:chapter|chap|ch\.?)[-_\s/]*(\d+(?:\.\d+)?)/i.exec(text)
    || /\/(\d+)[-_][^/]+$/.exec(text);
  const parsed = match ? Number(match[1]) : Number.NaN;
  return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : fallback;
}

function uniqueBy(items, keyFn) {
  const seen = new Set();
  const result = [];
  for (const item of items) {
    const key = keyFn(item);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    result.push(item);
  }
  return result;
}

function contentItem({ id, title, subtitle = "", coverUrl = "", detailUrl = "", sourceName, synopsis = "" }) {
  return {
    id,
    title,
    subtitle,
    coverUrl,
    detailUrl,
    sourceName,
    kind: "novel",
    synopsis
  };
}

function chapterItem({ title, url, chapterNumber }) {
  return {
    title: title || `Chapter ${chapterNumber}`,
    url,
    chapterNumber
  };
}

const royalRoad = {
  name: "RoyalRoad",
  baseUrl: "https://www.royalroad.com",

  async search(query, page = 1) {
    const html = await fetchText(`${this.baseUrl}/fictions/search?title=${encodeURIComponent(query)}&page=${page}`);
    if (blocked(html)) return [];
    const matches = [...html.matchAll(/<h2[^>]*class=["'][^"']*fiction-title[^"']*["'][\s\S]*?<a[^>]+href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)];
    return uniqueBy(matches.map((match) => {
      const start = Math.max(0, match.index - 1600);
      const end = Math.min(html.length, match.index + 2400);
      const nearby = html.slice(start, end);
      const href = match[1];
      const title = htmlToText(match[2]);
      if (!title) return null;
      const image = attr((/<img\b[\s\S]*?>/i.exec(nearby) || [""])[0], "src");
      const labels = [...nearby.matchAll(/<span[^>]*class=["'][^"']*label[^"']*["'][^>]*>([\s\S]*?)<\/span>/gi)]
        .map((label) => htmlToText(label[1]))
        .filter(Boolean)
        .slice(0, 4)
        .join(", ");
      return contentItem({
        id: `royalroad_${href.split("/").filter(Boolean).slice(1, 2).join("") || href}`,
        title,
        subtitle: labels || "RoyalRoad",
        coverUrl: absoluteUrl(this.baseUrl, image),
        detailUrl: absoluteUrl(this.baseUrl, href),
        sourceName: this.name,
        synopsis: htmlToText((/<div[^>]*class=["'][^"']*fiction-stats[^"']*["'][\s\S]*?<\/div>/i.exec(nearby) || [""])[0])
      });
    }).filter(Boolean), (item) => item.detailUrl).slice(0, 30);
  },

  async popular(page = 1) {
    const queries = ["mother of learning", "beware of chicken", "super supportive", "the perfect run"];
    const query = queries[(Math.max(1, page) - 1) % queries.length];
    return this.search(query, 1);
  },

  async chapters(detailUrl) {
    const html = await fetchText(detailUrl, { headers: { referer: this.baseUrl } });
    if (blocked(html)) return [];
    const links = [...html.matchAll(/<a[^>]+href=["']([^"']*\/chapter\/[^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)]
      .map((match, index) => {
        const href = match[1];
        const title = htmlToText(match[2]).replace(/\s*-\s*stub\s*$/i, "");
        return chapterItem({
          title: title || `Chapter ${index + 1}`,
          url: absoluteUrl(this.baseUrl, href),
          chapterNumber: chapterNumberFrom(`${href} ${title}`, index + 1)
        });
      });
    return uniqueBy(links, (item) => item.url).sort((a, b) => a.chapterNumber - b.chapterNumber);
  },

  async chapterText(chapterUrl) {
    const html = await fetchText(chapterUrl, { headers: { referer: this.baseUrl } });
    if (blocked(html)) return "";
    const match = /<div[^>]*class=["'][^"']*chapter-content[^"']*["'][^>]*>([\s\S]*?)<\/div>\s*<\/div>/i.exec(html)
      || /<div[^>]*class=["'][^"']*chapter-content[^"']*["'][^>]*>([\s\S]*?)<\/div>/i.exec(html);
    return htmlToText(match ? match[1] : "");
  }
};

const wuxiaWorld = {
  name: "Wuxiaworld",
  baseUrl: "https://www.wuxiaworld.com",

  async search(query) {
    const payload = await fetchJson(`${this.baseUrl}/api/novels/search?query=${encodeURIComponent(query)}`, {
      headers: { referer: `${this.baseUrl}/novels` }
    });
    return (payload.items || []).map((item) => contentItem({
      id: `wuxiaworld_${item.slug}`,
      title: decodeHtml(item.name),
      subtitle: item.authorName || (item.genres || []).slice(0, 3).join(", ") || "Wuxiaworld",
      coverUrl: item.coverUrl || "",
      detailUrl: `${this.baseUrl}/novel/${item.slug}`,
      sourceName: this.name,
      synopsis: htmlToText(item.synopsis || "")
    })).filter((item) => item.title && item.detailUrl).slice(0, 30);
  },

  async popular(page = 1) {
    const queries = ["a will eternal", "renegade immortal", "coiling dragon", "i shall seal the heavens"];
    const query = queries[(Math.max(1, page) - 1) % queries.length];
    return this.search(query);
  },

  async chapters(detailUrl) {
    const html = await fetchText(detailUrl, { headers: { referer: this.baseUrl } });
    if (blocked(html)) return [];
    const slug = /\/novel\/([^/?#]+)/i.exec(detailUrl)?.[1] || "";
    if (!slug) return [];
    const linked = [...html.matchAll(new RegExp(`<a[^>]+href=["']([^"']*/novel/${slug}/[^"']*chapter[^"']*)["'][^>]*>([\\s\\S]*?)<\\/a>`, "gi"))]
      .map((match, index) => chapterItem({
        title: htmlToText(match[2]) || `Chapter ${index + 1}`,
        url: absoluteUrl(this.baseUrl, match[1]),
        chapterNumber: chapterNumberFrom(match[1], index + 1)
      }));
    const linkedChapters = uniqueBy(linked, (item) => item.url).sort((a, b) => a.chapterNumber - b.chapterNumber);
    if (linkedChapters.length > 1) return linkedChapters;

    const chapterSlug = /"slug":"([^"]*chapter-\d+)"/i.exec(html)?.[1] || "";
    const prefix = chapterSlug ? chapterSlug.replace(/chapter-\d+.*/i, "") : "";
    const latest = Number(/chapterCount":\{"value":(\d+)/i.exec(html)?.[1] || 0);
    if (!prefix || !latest) return linkedChapters;
    return Array.from({ length: latest }, (_, index) => {
      const number = index + 1;
      return chapterItem({
        title: `Chapter ${number}`,
        url: `${this.baseUrl}/novel/${slug}/${prefix}chapter-${number}`,
        chapterNumber: number
      });
    });
  },

  async chapterText(chapterUrl) {
    const html = await fetchText(chapterUrl, { headers: { referer: chapterUrl.split("/").slice(0, -1).join("/") || this.baseUrl } });
    if (blocked(html)) return "";
    const match = /<div[^>]*class=["'][^"']*(?:chapter-content|chapter-body|fr-view)[^"']*["'][^>]*>([\s\S]*?)<\/div>\s*<\/div>/i.exec(html)
      || /<article[^>]*>([\s\S]*?)<\/article>/i.exec(html)
      || /"content":"((?:\\"|[^"])*)"/i.exec(html);
    const raw = match ? match[1].replace(/\\"/g, "\"").replace(/\\u003C/g, "<").replace(/\\u003E/g, ">").replace(/\\n/g, "\n") : "";
    return htmlToText(raw);
  }
};

const freeWebNovel = {
  name: "FreeWebNovel",
  baseUrl: "https://freewebnovel.com",

  async search(query) {
    const body = new URLSearchParams({ searchkey: query }).toString();
    const html = await fetchText(`${this.baseUrl}/search`, {
      method: "POST",
      headers: {
        "content-type": "application/x-www-form-urlencoded",
        referer: `${this.baseUrl}/`
      },
      body
    });
    if (blocked(html)) return [];
    const links = [...html.matchAll(/<a[^>]+href=["']([^"']+)["'][^>]*(?:title=["']([^"']+)["'])?[^>]*>([\s\S]*?)<\/a>/gi)]
      .map((match) => {
        const href = match[1];
        if (!/\/(?:book|novel|webnovel|read|[^/]+)(?:\.html)?$/i.test(href) || /chapter/i.test(href)) return null;
        const title = decodeHtml(match[2] || htmlToText(match[3]));
        if (!title || title.length < 3 || /^(home|genres|popular|search)$/i.test(title)) return null;
        const nearby = html.slice(Math.max(0, match.index - 1000), Math.min(html.length, match.index + 1600));
        const imgTag = /<img\b[\s\S]*?>/i.exec(nearby)?.[0] || "";
        return contentItem({
          id: `freewebnovel_${href.replace(/[^a-z0-9]+/gi, "_")}`,
          title,
          subtitle: "FreeWebNovel",
          coverUrl: absoluteUrl(this.baseUrl, attr(imgTag, "data-src") || attr(imgTag, "src")),
          detailUrl: absoluteUrl(this.baseUrl, href),
          sourceName: this.name,
          synopsis: ""
        });
      })
      .filter(Boolean);
    return uniqueBy(links, (item) => item.detailUrl).slice(0, 20);
  },

  async popular(page = 1) {
    const html = await fetchText(`${this.baseUrl}/sort/most-popular?page=${Math.max(1, page)}`);
    if (blocked(html)) return [];
    const links = [...html.matchAll(/<a[^>]+href=["']([^"']+)["'][^>]*(?:title=["']([^"']+)["'])?[^>]*>([\s\S]*?)<\/a>/gi)]
      .map((match) => {
        const href = match[1];
        const title = decodeHtml(match[2] || htmlToText(match[3]));
        if (!title || /chapter|genre|home|login|register/i.test(`${title} ${href}`)) return null;
        return contentItem({
          id: `freewebnovel_${href.replace(/[^a-z0-9]+/gi, "_")}`,
          title,
          subtitle: "FreeWebNovel",
          coverUrl: "",
          detailUrl: absoluteUrl(this.baseUrl, href),
          sourceName: this.name
        });
      })
      .filter(Boolean);
    return uniqueBy(links, (item) => item.detailUrl).slice(0, 20);
  },

  async chapters(detailUrl) {
    const html = await fetchText(detailUrl, { headers: { referer: this.baseUrl } });
    if (blocked(html)) return [];
    const slug = new URL(detailUrl).pathname.split("/").filter(Boolean).pop()?.replace(/\.html$/i, "") || "";
    const links = [...html.matchAll(/<a[^>]+href=["']([^"']*chapter[^"']*)["'][^>]*>([\s\S]*?)<\/a>/gi)]
      .map((match, index) => {
        const href = match[1];
        const title = htmlToText(match[2]);
        if (slug && !href.includes(slug) && !href.includes("chapter")) return null;
        return chapterItem({
          title: title || `Chapter ${index + 1}`,
          url: absoluteUrl(this.baseUrl, href),
          chapterNumber: chapterNumberFrom(`${href} ${title}`, index + 1)
        });
      })
      .filter(Boolean);
    return uniqueBy(links, (item) => item.url).sort((a, b) => a.chapterNumber - b.chapterNumber);
  },

  async chapterText(chapterUrl) {
    const html = await fetchText(chapterUrl, { headers: { referer: this.baseUrl } });
    if (blocked(html)) return "";
    const match = /<div[^>]+id=["']chapter-content["'][^>]*>([\s\S]*?)<\/div>/i.exec(html)
      || /<div[^>]+class=["'][^"']*(?:chapter-content|cha-words|txt)[^"']*["'][^>]*>([\s\S]*?)<\/div>/i.exec(html)
      || /<article[^>]*>([\s\S]*?)<\/article>/i.exec(html);
    return htmlToText(match ? match[1] : "");
  }
};

const sources = process.env.ENABLE_FREEWEBNOVEL === "1"
  ? [wuxiaWorld, royalRoad, freeWebNovel]
  : [wuxiaWorld, royalRoad];

function sourceFor(nameOrUrl = "") {
  const value = String(nameOrUrl || "").toLowerCase();
  return sources.find((source) =>
    value.includes(source.name.toLowerCase())
    || value.includes(new URL(source.baseUrl).hostname.replace(/^www\./, ""))
  ) || null;
}

async function searchNovels(query, page = 1) {
  const clean = String(query || "").trim();
  if (!clean) return popularNovels(page);
  const settled = await Promise.allSettled(sources.map((source) => source.search(clean, page)));
  return uniqueBy(settled.flatMap((result) => result.status === "fulfilled" ? result.value : []), (item) => item.detailUrl)
    .slice(0, 40);
}

async function popularNovels(page = 1) {
  const settled = await Promise.allSettled(sources.map((source) => source.popular(page)));
  return uniqueBy(settled.flatMap((result) => result.status === "fulfilled" ? result.value : []), (item) => item.detailUrl)
    .slice(0, 40);
}

async function novelChapters({ detailUrl, sourceName }) {
  const source = sourceFor(`${sourceName} ${detailUrl}`);
  if (!source || !detailUrl || detailUrl.startsWith("novel://")) return [];
  return source.chapters(detailUrl);
}

async function novelChapterText({ chapterUrl, sourceName }) {
  const source = sourceFor(`${sourceName} ${chapterUrl}`);
  if (!source || !chapterUrl || chapterUrl.startsWith("novel-chapter://")) return "";
  return source.chapterText(chapterUrl);
}

module.exports = {
  searchNovels,
  popularNovels,
  novelChapters,
  novelChapterText
};
