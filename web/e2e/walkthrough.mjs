// End-to-end check of the web page in a real Chrome, against a running SplitLedger (API + page).
//
//   npm run e2e -- http://localhost:8080
//   REGISTRATION_CODE=... SCREENSHOTS=shots npm run e2e -- http://3.75.197.30:8080
//
// Runs the friends' main path twice (desktop light, phone dark) and fails on any wrong state or
// any JavaScript error in the browser. Uses the installed Chrome (playwright-core, no download).
import { chromium } from 'playwright-core';
import { fileURLToPath } from 'node:url';
import { mkdirSync } from 'node:fs';

const base = process.argv[2] ?? 'http://localhost:8080';
const code = process.env.REGISTRATION_CODE ?? '';
const shots = process.env.SCREENSHOTS;
const csv = fileURLToPath(new URL('../../samples/sparkasse-csv-camt-2026-03-a.csv', import.meta.url));
const ownIban = 'DE81 0000 1000 0001 2345 67'; // the synthetic account the sample statement belongs to

if (shots) mkdirSync(shots, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome' });
const problems = [];

function check(condition, message) {
  if (!condition) throw new Error(`Check failed: ${message}`);
}

async function walkthrough(name, options) {
  const context = await browser.newContext(options);
  const page = await context.newPage();
  page.on('pageerror', (e) => problems.push(`${name}: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') problems.push(`${name}: console: ${m.text()}`); });
  const shot = async (step) => { if (shots) await page.screenshot({ path: `${shots}/${name}-${step}.png`, fullPage: true }); };

  await page.goto(base);
  await page.getByText('Shared expenses, straight from your bank statement.').waitFor();
  await shot('1-welcome');

  // Register, then the one-time token screen.
  await page.getByLabel('Your name').fill('Anna Beispiel');
  if (code) await page.getByLabel('Registration code').fill(code);
  await page.getByRole('button', { name: 'Create account' }).last().click();
  check((await page.locator('.token').innerText()).startsWith('slt_'), 'token is shown once after registering');
  await page.getByRole('button', { name: 'I saved it, continue' }).click();

  await page.getByPlaceholder('e.g. WG Kessenich').fill('WG Kessenich');
  await page.getByRole('button', { name: 'Create' }).click();
  await page.getByText('You are all settled').waitFor();

  // Members: the invite code stays until closed; "IBAN saved" goes away by itself.
  await page.getByRole('tab', { name: 'Members' }).click();
  await page.getByPlaceholder('e.g. Ben').fill('Ben');
  await page.getByRole('button', { name: 'Invite' }).click();
  const invite = page.getByText(/Send Ben this invite code: inv_/);
  await invite.waitFor();
  await page.waitForTimeout(5500);
  check(await invite.isVisible(), 'invite code is still visible after 5 seconds');
  await page.getByRole('button', { name: 'Dismiss' }).click();
  await invite.waitFor({ state: 'detached' });
  await page.getByLabel('IBAN').fill(ownIban);
  await page.getByRole('button', { name: 'Save' }).click();
  const saved = page.getByText('IBAN saved');
  await saved.waitFor();
  await saved.waitFor({ state: 'detached', timeout: 8000 });
  await shot('2-members');

  // An expense only Ben shares: Ben owes Anna 42,00 €.
  await page.getByRole('tab', { name: 'Add' }).click();
  await page.getByLabel('What for?').fill('Groceries');
  await page.getByLabel('Amount (€)').fill('42,00');
  await page.getByRole('button', { name: 'Anna Beispiel', exact: true }).click();
  await shot('3-add');
  await page.getByRole('button', { name: /^Add 42,00/ }).click();
  await page.getByText('You get back 42,00 €').waitFor();

  // GiroCode for the suggested payment.
  await page.getByRole('button', { name: 'Show GiroCode' }).click();
  const qr = page.locator('.qr-box img');
  await qr.waitFor();
  check(await qr.evaluate((img) => img.complete && img.naturalWidth > 100), 'GiroCode image loaded');
  await shot('4-overview');

  // Bank import: 8 outgoing payments become private candidates; share one.
  await page.getByRole('tab', { name: /Bank/ }).click();
  await page.locator('input[type=file]').setInputFiles(csv);
  await page.getByText('8 new · 0 already imported · 1 skipped').waitFor();
  await shot('5-bank');
  await page.locator('li', { hasText: 'EDEKA Center Bonn' }).getByRole('button', { name: 'Share' }).click();
  await page.getByText('Shared equally with everyone').waitFor();

  // History shows everything without searching; search narrows it, Clear brings it back.
  await page.getByRole('tab', { name: 'History' }).click();
  const rows = page.locator('.rows li');
  await rows.nth(1).waitFor();
  check(await rows.count() === 2, 'history lists both expenses without a search');
  await page.getByLabel('Search').fill('Edeca'); // one wrong letter
  await page.getByRole('button', { name: 'Search' }).click();
  await page.getByText('Results for “Edeca”').waitFor();
  check(await rows.count() === 1 && (await rows.first().innerText()).includes('EDEKA Center Bonn'), 'typo search finds EDEKA');
  await shot('6-history');
  await page.getByRole('button', { name: 'Clear' }).click();
  await page.getByText('Results for').waitFor({ state: 'detached' });
  check(await rows.count() === 2, 'Clear shows the whole history again');

  // On a phone the tabs are a bar at the bottom edge of the screen.
  if (options.viewport.width < 640) {
    const bar = await page.locator('.tabs').boundingBox();
    check(bar && Math.abs(bar.y + bar.height - options.viewport.height) < 2, 'tab bar sits at the bottom of the screen');
  }
  await context.close();
}

try {
  await walkthrough('desktop', { viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
  await walkthrough('phone', { viewport: { width: 390, height: 844 }, colorScheme: 'dark', isMobile: true, hasTouch: true });
} finally {
  await browser.close();
}
if (problems.length > 0) {
  console.error(`Browser errors:\n${problems.join('\n')}`);
  process.exit(1);
}
console.log('Walkthrough passed: desktop and phone');
