(async () => {
  const id = '32d76d19-8a05-4db0-9fc2-e0b0648fe9d0'; // Solo Leveling (Na Honjaman Level-Up)
  const urls = [
    'https://api.mangadex.org/manga/' + id + '/feed?translatedLanguage[]=en&limit=500&order[chapter]=asc&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica&includeExternalUrl=0',
    'https://api.mangadex.org/manga/' + id + '/feed?translatedLanguage[]=en&limit=500&order[chapter]=asc',
    'https://api.mangadex.org/manga/' + id + '/feed?translatedLanguage%5B%5D=en&limit=100&order%5Bchapter%5D=asc'
  ];
  for (const u of urls) {
    try {
      const r = await fetch(u);
      const j = await r.json();
      const n = (j.data && j.data.length) || 0;
      console.log(r.status, 'chapters:', n, '|', u.slice(u.indexOf('/feed')));
      if (j.errors) console.log('  errors:', JSON.stringify(j.errors).slice(0, 200));
      if (r.status === 400) console.log('  result:', JSON.stringify(j).slice(0, 200));
    } catch (e) {
      console.log('ERR', e.message, '|', u.slice(u.indexOf('/feed')));
    }
  }
  process.exit(0);
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
