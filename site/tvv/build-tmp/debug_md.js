const store = {};
global.localStorage = {
  getItem: k => store[k] || null,
  setItem: (k, v) => { store[k] = String(v); },
  removeItem: k => { delete store[k]; }
};
global.window = { dispatchEvent: () => {}, addEventListener: () => {} };
global.document = { dispatchEvent: () => {} };
const api = require('/home/masteralex/Desktop/novelapp/vidaa-tv/api.js');

(async () => {
  // mangadexChapters through the fixed function
  const chs = await api.mangadexChapters('32d76d19-8a05-4db0-9fc2-e0b0648fe9d0');
  console.log('md chapters:', chs.length, '| first:', chs[0] && chs[0].title, '| last:', chs[chs.length - 1] && chs[chs.length - 1].title);
  if (chs.length) {
    const pages = await api.mangadexPages(chs[0].id);
    console.log('md pages:', pages.length, pages[0]);
  }
  // by-title fuzzy fallback
  const byTitle = await api.mangadexChaptersByTitle('Solo Leveling');
  console.log('byTitle chapters:', byTitle.length);
  process.exit(0);
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
