// End-to-end check of the web page in a real Chrome, against a running SplitLedger (API + page).
//
//   npm run e2e -- http://localhost:8080
//   REGISTRATION_CODE=... SCREENSHOTS=shots npm run e2e -- https://splitledger.example.org
//
// Runs the friends' main path twice (desktop light, phone dark), with two people in two browser
// contexts: Anna starts a group and sends Ben an invite link; Ben opens it and picks a password.
// Fails on any wrong state or any JavaScript error in the browser. Uses the installed Chrome
// (playwright-core, no download).
import { chromium } from 'playwright-core';
import { fileURLToPath } from 'node:url';
import { mkdirSync } from 'node:fs';

const base = process.argv[2] ?? 'http://localhost:8080';
const code = process.env.REGISTRATION_CODE ?? '';
const shots = process.env.SCREENSHOTS;
const csv = fileURLToPath(new URL('../../samples/sparkasse-csv-camt-2026-03-a.csv', import.meta.url));
const ownIban = 'DE81 0000 1000 0001 2345 67'; // the synthetic account the sample statement belongs to
const run = Date.now().toString(36); // usernames are unique per database, so every run gets fresh ones

if (shots) mkdirSync(shots, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome' });
const problems = [];

function check(condition, message) {
  if (!condition) throw new Error(`Check failed: ${message}`);
}

/** Waits until the element shows the text (data reloads after every change), else fails with the message. */
async function shows(locator, text, message) {
  try {
    await locator.filter({ hasText: text }).first().waitFor({ timeout: 10000 });
  } catch {
    throw new Error(`Check failed: ${message} (shows: ${await locator.first().innerText().catch(() => 'nothing')})`);
  }
}

async function open(name, options) {
  const context = await browser.newContext(options);
  const page = await context.newPage();
  page.on('pageerror', (e) => problems.push(`${name}: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') problems.push(`${name}: console: ${m.text()}`); });
  // "Delete …?", "Undo …?" and similar confirmations: answer yes.
  page.on('dialog', (d) => void d.accept());
  const shot = async (step) => { if (shots) await page.screenshot({ path: `${shots}/${name}-${step}.png`, fullPage: true }); };
  return { context, page, shot };
}

async function walkthrough(name, options) {
  const anna = await open(`${name}-anna`, options);
  const { page, shot } = anna;
  const phone = options.viewport.width < 960;
  const nav = (label) => page.getByRole('link', { name: label, exact: true }).click();
  const addExpense = () => page.getByRole('button', { name: 'Add expense' }).first().click();
  const card = page.locator('.group-card');
  const person = (who) => page.locator('.people li').filter({ hasText: who }).filter({ visible: true }).first();
  const toast = (text) => page.locator('.toast', { hasText: text }).waitFor();

  await page.goto(base);
  await page.getByText('Who paid what, and who owes whom.').waitFor();
  await shot('01-sign-in');

  // Anna starts a group: account with username and password (plus the host code if one is set).
  await page.getByRole('button', { name: 'Start a new group' }).click();
  await page.getByLabel('Your name').fill('Anna Beispiel');
  await page.getByLabel('Username').fill(`anna-${name}-${run}`);
  await page.getByLabel('Password').fill('anna password');
  if (code) await page.getByLabel('Host code').fill(code);
  await page.getByRole('button', { name: 'Create account' }).click();
  await page.getByLabel('Group name').fill('WG Kessenich');
  await page.getByRole('button', { name: 'Create' }).click();
  await card.getByText('All settled').waitFor();

  // Invite Ben: the page hands Anna a link to send; her own IBAN goes in for the GiroCode.
  await nav('Group');
  await page.getByLabel("Friend's name").fill('Ben');
  await page.getByRole('button', { name: 'Invite' }).click();
  const inviteLink = await page.getByLabel('Link').inputValue();
  check(/#join=inv_/.test(inviteLink), 'invite link carries the code in the fragment');
  await shot('02-invite-link');
  await page.getByRole('button', { name: 'Close' }).click();
  await page.getByLabel('IBAN').fill(ownIban);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await toast('IBAN saved');

  // Ben opens the link on his phone, without an account, and is in the group with a password.
  const ben = await open(`${name}-ben`, options);
  await ben.page.goto(inviteLink.replace(/^https?:\/\/[^/]+/, base));
  await ben.page.getByText('Anna Beispiel invited you to').waitFor();
  check(await ben.page.getByLabel('Username').inputValue() === 'ben', 'username suggested from the name');
  await ben.shot('03-invite');
  await ben.page.getByLabel('Username').fill(`ben-${name}-${run}`);
  await ben.page.getByLabel('Password').fill('ben password');
  await ben.page.getByRole('button', { name: 'Join WG Kessenich' }).click();
  // The invite screen has a card too; the group name in the card's top row is only on the home screen.
  await ben.page.locator('.group-card .card-name', { hasText: 'WG Kessenich' }).waitFor();
  check(!ben.page.url().includes('inv_'), 'the invite code leaves the URL after joining');

  // An expense only the payer shares is pointless: the form refuses it and says why.
  await page.reload();
  await nav('Home');
  await addExpense();
  await page.getByLabel('Amount in euros').fill('42,00');
  await page.getByLabel('What for?').fill('REWE');
  check(await page.getByRole('button', { name: /Groceries/ }).getAttribute('aria-pressed') === 'true', 'REWE suggests groceries');
  await page.getByRole('button', { name: 'Ben shares' }).click();
  await page.getByText('Only Anna Beispiel takes part').waitFor();
  check(await page.getByRole('button', { name: /^Add\s42,00/ }).isDisabled(), 'payer-only expense cannot be saved');

  // Equally between Ben only: Ben owes Anna 42,00 €. The chips show each share.
  await page.getByRole('button', { name: 'Ben shares' }).click();
  await page.getByRole('button', { name: 'Anna Beispiel shares' }).click();
  check((await page.getByRole('button', { name: 'Ben shares' }).innerText()).includes('42,00'), 'preview shows Ben pays 42,00');
  await shot('04-add');
  await page.getByRole('button', { name: /^Add\s42,00/ }).click();
  await toast('Expense added');
  await shows(card, '42,00', 'card says you get back 42,00');

  // Exact amounts: Anna 10,00 of 30,00; "Split the rest" gives Ben the missing 20,00.
  await addExpense();
  await page.getByLabel('Amount in euros').fill('30,00');
  await page.getByLabel('What for?').fill('Concert tickets');
  await page.getByText('Split unevenly').click();
  await page.getByRole('button', { name: 'Amounts', exact: true }).click();
  await page.getByLabel('Amount for Anna Beispiel').fill('10');
  await page.getByText('10,00 € of 30,00 € assigned').waitFor();
  check(await page.getByRole('button', { name: /^Add\s30,00/ }).isDisabled(), 'cannot save before the amounts add up');
  await page.getByRole('button', { name: 'Split the rest' }).click();
  check(await page.getByLabel('Amount for Ben').inputValue() === '20,00', 'the rest goes to Ben');
  await shot('05-exact');
  await page.getByRole('button', { name: /^Add\s30,00/ }).click();
  await toast('Expense added');
  await shows(card, '62,00', 'card says you get back 62,00');
  await shows(person('Ben'), '62,00', 'people list says Ben owes you 62,00');
  await shot('06-home');

  // Ben sees what Anna did in his activity feed, with a dot on the tab first.
  await ben.page.reload();
  await ben.page.locator('.nav-dot').filter({ visible: true }).first().waitFor();
  await ben.page.getByRole('link', { name: /^Activity/ }).click(); // "Activity, 2 new"
  await ben.page.getByText('Anna Beispiel added REWE').waitFor();
  await ben.page.getByText('You joined').waitFor();
  await ben.shot('07-activity');

  // Settling up: the GiroCode for Ben to scan, then Anna marks the money as received.
  await person('Ben').click();
  await page.getByRole('button', { name: 'Show GiroCode' }).click();
  const qr = page.locator('.qr img');
  await qr.waitFor();
  check(await qr.evaluate((img) => img.complete && img.naturalWidth > 100), 'GiroCode image loaded');
  await shot('08-settle');
  await page.getByRole('button', { name: 'Mark as received' }).click();
  await page.getByRole('heading', { name: 'Ben paid you' }).waitFor();
  await page.getByRole('button', { name: 'Done' }).click();
  await card.getByText('All settled').waitFor();

  // Bank import: 8 outgoing payments become private candidates. Share opens the form filled in
  // from the bank, where payer, date and split can still be changed.
  await page.getByRole('button', { name: /Import/ }).first().click();
  await page.locator('input[type=file]').setInputFiles(csv);
  await toast('8 new payments to sort out');
  await shot('09-bank');
  await page.locator('li', { hasText: 'EDEKA Center Bonn' }).getByRole('button', { name: 'Share' }).click();
  await page.getByRole('heading', { name: 'Share a bank payment' }).waitFor();
  check(await page.getByLabel('What for?').inputValue() === 'EDEKA Center Bonn', 'share form takes the name from the bank');
  check(await page.getByLabel('Amount in euros').inputValue() === '54,12', 'share form takes the amount from the bank');
  check(await page.getByRole('button', { name: /Groceries/ }).getAttribute('aria-pressed') === 'true', 'EDEKA suggests groceries');
  await page.getByRole('button', { name: /^Share\s54,12/ }).click();
  await toast('Shared “EDEKA Center Bonn” with the group');

  // History lists everything; search with a typo narrows it, clearing brings it back.
  await nav(phone ? 'Home' : 'History');
  if (phone) await page.getByRole('button', { name: 'See all' }).click();
  await page.getByRole('heading', { name: 'History' }).waitFor();
  const rows = page.locator('.main .list li');
  await rows.filter({ hasText: 'REWE' }).waitFor(); // past the loading placeholders
  check(await rows.count() === 3, `history lists all three expenses without a search: ${await rows.allInnerTexts()}`);
  await page.getByLabel('Search expenses').fill('Edeca'); // one wrong letter
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await page.getByText('Best matches for “Edeca”').waitFor();
  check(await rows.count() === 1 && (await rows.first().innerText()).includes('EDEKA Center Bonn'), 'typo search finds EDEKA');
  await shot('10-history');
  await page.getByLabel('Search expenses').fill('');
  await page.getByText('Best matches for').waitFor({ state: 'detached' });
  check(await rows.count() === 3, 'clearing the search shows the whole history again');
  await page.getByRole('button', { name: 'Groceries' }).click();
  await page.waitForFunction(() => document.querySelectorAll('.main .list li').length === 2);
  check((await rows.allInnerTexts()).every((t) => /REWE|EDEKA/.test(t)), 'groceries filter shows REWE and EDEKA');

  // A third person: Lucy pays the train for Anna and herself. Anna now owes Lucy while Ben owes
  // Anna, and the people list says exactly that instead of net numbers.
  await nav('Group');
  await page.getByLabel("Friend's name").fill('Lucy');
  await page.getByRole('button', { name: 'Invite' }).click();
  await page.getByLabel('Link').waitFor();
  await page.getByRole('button', { name: 'Close' }).click();
  await addExpense();
  await page.getByLabel('Amount in euros').fill('20,00');
  await page.getByLabel('What for?').fill('Train');
  await page.getByRole('group', { name: 'Paid by' }).getByRole('button', { name: /Lucy/ }).click();
  await page.getByRole('button', { name: 'Ben shares' }).click();
  await page.getByRole('button', { name: /^Add\s20,00/ }).click();
  await toast('Expense added');
  await nav('Home');
  await shows(person('Lucy'), '10,00', 'you owe Lucy 10,00');
  await shows(person('Ben'), '27,06', 'Ben owes you 27,06');
  await shot('11-who-owes-whom');

  // Corrections: undo Ben's payment, delete the train, edit EDEKA down to 50,00. Nothing
  // disappears; the old entries stay crossed out with who changed them.
  await nav(phone ? 'Home' : 'History');
  if (phone) await page.getByRole('button', { name: 'See all' }).click();
  await page.getByRole('button', { name: /^Payments/ }).click();
  await page.locator('.main .list li', { hasText: 'Ben paid you' }).getByRole('button', { name: 'Undo' }).click();
  await toast('Payment undone');
  await page.getByText('undone by Anna Beispiel').waitFor();
  await page.getByRole('button', { name: 'Expenses', exact: true }).click();
  await page.locator('.main .list li', { hasText: 'Train' }).click();
  await page.getByRole('button', { name: 'Delete' }).click();
  await toast('Deleted “Train”');
  await page.locator('.main .list li', { hasText: 'Train' }).getByText(/Deleted by Anna Beispiel/).waitFor();
  await page.locator('.main .list li', { hasText: 'EDEKA Center Bonn' }).first().click();
  await page.getByRole('button', { name: 'Edit' }).click();
  await page.getByRole('heading', { name: 'Edit expense' }).waitFor();
  await page.getByLabel('Amount in euros').fill('50,00');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await toast('Expense updated');
  await page.getByText(/Old version, edited by Anna Beispiel/).waitFor();
  await shot('12-corrections');
  await nav('Home');
  // 62,00 again after the undo, plus half of the edited 50,00; the train no longer counts.
  await shows(person('Ben'), '87,00', 'Ben owes you 87,00 after the corrections');
  check(!(await person('Lucy').innerText()).includes('10,00'), 'deleted train no longer counts');

  // Insights: this month's spending, by category.
  await nav('Insights');
  await page.getByRole('heading', { name: 'Per month' }).waitFor();
  await page.locator('.chart-card', { hasText: 'By category' }).getByText('Groceries').waitFor();
  await shot('13-insights');

  // Ben signs out and back in with his password, as on a new phone.
  await ben.page.locator('.sidebar .account, .topbar [aria-label="Your account"]').filter({ visible: true }).click();
  await ben.page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await ben.page.getByLabel('Username').fill(`ben-${name}-${run}`);
  await ben.page.getByLabel('Password').fill('ben password');
  await ben.page.getByRole('button', { name: 'Sign in', exact: true }).click();
  // Back where he was (Activity); Home shows his balance.
  await ben.page.getByRole('link', { name: 'Home', exact: true }).click();
  await ben.page.locator('.group-card .card-name').waitFor();
  await shows(ben.page.locator('.group-card'), '87,00', 'Ben signed in again and owes 87,00');
  await ben.shot('14-home');

  // On a phone the navigation is a bar at the bottom edge of the screen.
  if (phone) {
    const bar = await page.locator('.bottom-nav').boundingBox();
    check(bar && Math.abs(bar.y + bar.height - options.viewport.height) < 2, 'navigation sits at the bottom of the screen');
  }
  await anna.context.close();
  await ben.context.close();
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
console.log('Walkthrough passed: desktop and phone, two friends each');
