#!/usr/bin/env node
/**
 * Package VIDAA TV App for Hisense VIDAA Developer Store submission.
 *
 * Usage:  node scripts/package-vidaa.js
 *
 * Produces:
 *   1. site/tv/          — deployable web directory (for static hosting)
 *   2. vidaa-tv-app.zip  — zip bundle for VIDAA store upload
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'vidaa-tv');
const SITE_TV = path.join(ROOT, 'site', 'tv');
const ZIP_NAME = 'vidaa-tv-app.zip';
const ZIP_PATH = path.join(ROOT, ZIP_NAME);

const FILES_TO_COPY = [
  'index.html',
  'tv.css',
  'spatial-nav.js',
  'qrcode.js',
  'api.js',
  'autoplay.js',
  'player.js',
  'app.js',
  'appinfo.json',
  'manifest.json'
];

const ASSET_FILES = [
  'assets/icon.png',
  'assets/icon.svg',
  'assets/largeIcon.png',
  'assets/splash.png'
];

function ensureDir(dir) {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
}

function copyFile(src, dest) {
  ensureDir(path.dirname(dest));
  fs.copyFileSync(src, dest);
}

function main() {
  console.log('📦 Packaging NovaRead TV for Hisense VIDAA Store...\n');

  // 1. Copy all source files to site/tv/
  console.log('1️⃣  Copying source files to site/tv/ ...');
  ensureDir(SITE_TV);
  ensureDir(path.join(SITE_TV, 'assets'));

  FILES_TO_COPY.forEach(f => {
    const src = path.join(SRC, f);
    const dest = path.join(SITE_TV, f);
    if (fs.existsSync(src)) {
      copyFile(src, dest);
      console.log(`   ✓ ${f}`);
    } else {
      console.warn(`   ⚠ Missing: ${f}`);
    }
  });

  ASSET_FILES.forEach(f => {
    const src = path.join(SRC, f);
    const dest = path.join(SITE_TV, f);
    if (fs.existsSync(src)) {
      copyFile(src, dest);
      console.log(`   ✓ ${f}`);
    } else {
      console.warn(`   ⚠ Missing asset: ${f}`);
    }
  });

  // Copy icon.svg from site/assets if it exists
  const siteIconSvg = path.join(ROOT, 'site', 'assets', 'novelapp-icon.svg');
  if (fs.existsSync(siteIconSvg)) {
    copyFile(siteIconSvg, path.join(SITE_TV, 'assets', 'icon.svg'));
    copyFile(siteIconSvg, path.join(SRC, 'assets', 'icon.svg'));
    console.log('   ✓ assets/icon.svg (from site/assets/novelapp-icon.svg)');
  }

  console.log('\n2️⃣  Creating VIDAA store ZIP bundle...');

  // 2. Build the zip
  if (fs.existsSync(ZIP_PATH)) {
    fs.unlinkSync(ZIP_PATH);
  }

  try {
    execSync(`cd "${SRC}" && zip -r "${ZIP_PATH}" . -x ".*"`, { stdio: 'pipe' });
    const stats = fs.statSync(ZIP_PATH);
    const sizeMB = (stats.size / (1024 * 1024)).toFixed(2);
    console.log(`   ✓ ${ZIP_NAME} created (${sizeMB} MB)\n`);
  } catch (e) {
    console.error('   ✗ zip command failed. Trying tar fallback...');
    try {
      execSync(`cd "${SRC}" && tar -czf "${ZIP_PATH.replace('.zip', '.tar.gz')}" .`, { stdio: 'pipe' });
      console.log(`   ✓ vidaa-tv-app.tar.gz created (use zip for store submission)\n`);
    } catch (e2) {
      console.error('   ✗ Could not create archive. Please install zip: sudo apt install zip');
    }
  }

  // 3. Validation checks
  console.log('3️⃣  Validation checks...');

  const requiredFiles = ['index.html', 'appinfo.json', 'tv.css', 'spatial-nav.js', 'qrcode.js', 'api.js', 'player.js', 'app.js'];
  let allGood = true;
  requiredFiles.forEach(f => {
    const exists = fs.existsSync(path.join(SITE_TV, f));
    console.log(`   ${exists ? '✅' : '❌'} ${f}`);
    if (!exists) allGood = false;
  });

  const iconExists = fs.existsSync(path.join(SITE_TV, 'assets', 'icon.png'));
  console.log(`   ${iconExists ? '✅' : '❌'} assets/icon.png`);

  // Check appinfo.json schema
  try {
    const appinfo = JSON.parse(fs.readFileSync(path.join(SRC, 'appinfo.json'), 'utf-8'));
    if (!appinfo.id || !appinfo.main || !appinfo.title) {
      console.log('   ⚠️  appinfo.json missing required fields (id, main, title)');
      allGood = false;
    } else {
      console.log(`   ✅ appinfo.json valid — id: ${appinfo.id}, v${appinfo.version}`);
    }
  } catch (e) {
    console.log('   ❌ appinfo.json parse error');
    allGood = false;
  }

  console.log('\n' + (allGood ? '🎉 Package ready!' : '⚠️  Some checks failed — review above.'));
  console.log('');
  console.log('📁 Deployable web directory:  site/tv/');
  console.log(`📦 VIDAA store submission:    ${ZIP_NAME}`);
  console.log('🌐 Live URL (after deploy):   https://novelapp1.onrender.com/tv');
  console.log('');
}

main();
