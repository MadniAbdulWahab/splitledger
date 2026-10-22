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

  // An expense only the payer shares is pointless: the form refuses it and says why.
  await page.getByRole('tab', { name: 'Add' }).click();
  await page.getByLabel('What for?').fill('Groceries');
  await page.getByLabel('Amount (€)').fill('42,00');
  await page.getByRole('button', { name: 'Ben shares' }).click();
  await page.getByText('Only Anna Beispiel takes part').waitFor();
  check(await page.getByRole('button', { name: /^Add 42,00/ }).isDisabled(), 'payer-only expense cannot be saved');

  // Equally between Ben only: Ben owes Anna 42,00 €. The preview shows each share.
  await page.getByRole('button', { name: 'Ben shares' }).click();
  await page.getByRole('button', { name: 'Anna Beispiel shares' }).click();
  check((await page.locator('.split-rows li', { hasText: 'Ben' }).innerText()).includes('42,00'), 'preview shows Ben pays 42,00');
  await shot('3-add');
  await page.getByRole('button', { name: /^Add 42,00/ }).click();
  await page.getByText('You get back 42,00 €').waitFor();

  // Exact amounts: Anna 10,00 of 30,00; "Split the rest" gives Ben the missing 20,00.
  await page.getByRole('tab', { name: 'Add' }).click();
  await page.getByLabel('What for?').fill('Concert tickets');
  await page.getByLabel('Amount (€)').fill('30,00');
  await page.getByRole('button', { name: 'Exact amounts' }).click();
  await page.getByLabel('Amount for Anna Beispiel').fill('10');
  await page.getByText('10,00 € of 30,00 € assigned · 20,00 € left').waitFor();
  check(await page.getByRole('button', { name: /^Add 30,00/ }).isDisabled(), 'cannot save before the amounts add up');
  await page.getByRole('button', { name: 'Split the rest' }).click();
  check(await page.getByLabel('Amount for Ben').inputValue() === '20,00', 'the rest goes to Ben');
  await page.getByText('30,00 € of 30,00 € assigned').waitFor();
  await shot('3b-exact');
  await page.getByRole('button', { name: /^Add 30,00/ }).click();
  await page.getByText('You get back 62,00 €').waitFor();

  // Who owes whom, from Anna's point of view.
  const benRow = page.locator('.payment', { hasText: 'Ben owes you' });
  check((await benRow.innerText()).includes('62,00'), 'overview says Ben owes you 62,00');

  // GiroCode for the suggested payment.
  await page.getByRole('button', { name: 'Show GiroCode' }).click();
  const qr = page.locator('.qr-box img');
  await qr.waitFor();
  check(await qr.evaluate((img) => img.complete && img.naturalWidth > 100), 'GiroCode image loaded');
  await shot('4-overview');

  // Ben pays (Anna shows him her GiroCode); the payment shows up in the overview and the history.
  await page.getByRole('button', { name: 'Mark as received' }).click();
  await page.getByText('You are all settled').waitFor();
  await page.getByText('Recent payments').waitFor();
  check((await page.locator('.rows li', { hasText: 'Ben paid Anna Beispiel' }).first().innerText()).includes('62,00'),
    'recent payments list Ben paid 62,00');

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
  await rows.nth(2).waitFor();
  check(await rows.count() === 3, 'history lists all three expenses without a search');
  await page.getByLabel('Search').fill('Edeca'); // one wrong letter
  await page.getByRole('button', { name: 'Search' }).click();
  await page.getByText('Results for “Edeca”').waitFor();
  check(await rows.count() === 1 && (await rows.first().innerText()).includes('EDEKA Center Bonn'), 'typo search finds EDEKA');
  await shot('6-history');
  await page.getByRole('button', { name: 'Clear' }).click();
  await page.getByText('Results for').waitFor({ state: 'detached' });
  check(await rows.count() === 3, 'Clear shows the whole history again');
  await page.getByRole('button', { name: /^Payments/ }).click();
  check((await rows.first().innerText()).includes('Ben paid Anna Beispiel'), 'History > Payments lists the payment');
  await shot('7-payments');

  // A third person: Lucy pays the train for Anna and herself. Anna now owes Lucy while Ben owes
  // Anna, and the overview says exactly that instead of net numbers.
  await page.getByRole('tab', { name: 'Members' }).click();
  await page.getByPlaceholder('e.g. Ben').fill('Lucy');
  await page.getByRole('button', { name: 'Invite' }).click();
  await page.getByText(/Send Lucy this invite code/).waitFor();
  await page.getByRole('tab', { name: 'Add' }).click();
  await page.getByLabel('What for?').fill('Train');
  await page.getByLabel('Amount (€)').fill('20,00');
  await page.getByLabel('Paid by').selectOption({ label: 'Lucy' });
  await page.getByRole('button', { name: 'Ben shares' }).click();
  await page.getByRole('button', { name: /^Add 20,00/ }).click();
  await page.getByText('You owe Lucy').waitFor();
  check((await page.locator('.payment', { hasText: 'You owe Lucy' }).innerText()).includes('10,00'), 'you owe Lucy 10,00');
  check((await page.locator('.payment', { hasText: 'Ben owes you' }).innerText()).includes('27,06'), 'Ben owes you 27,06');
  await shot('8-who-owes-whom');

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
