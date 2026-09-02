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
  console.time('rnf_popular');
  const pop = await api.readNovelFullPopular(1);
  console.timeEnd('rnf_popular');
  console.log('RNF popular:', pop.length, pop.slice(0, 3).map(i => i.title + ' | ' + (i.coverUrl || 'no-cover').slice(0, 60)));

  if (pop.length) {
    console.time('rnf_chapters');
    const chs = await api.fetchChapters('novel', pop[0].detailPageUrl, pop[0].title, 'ReadNovelFull');
    console.timeEnd('rnf_chapters');
    console.log('RNF chapters:', chs.length, '| first:', chs[0] && chs[0].title, '| last:', chs[chs.length - 1] && chs[chs.length - 1].title);

    if (chs.length) {
      console.time('rnf_text');
      const text = await api.fetchChapterText(chs[0].url, chs[0].title, 'ReadNovelFull');
      console.timeEnd('rnf_text');
      console.log('RNF chapter text length:', text.length, '| preview:', JSON.stringify(text.slice(0, 120)));
    }
  }

  console.time('wc_chapters');
  const wc = await api.weebCentralChapters('01J76XYCPSY3C4BNPBRY8JMCBE');
  console.timeEnd('wc_chapters');
  console.log('WC chapters (regression):', wc.length);
  process.exit(0);
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
