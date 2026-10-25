// What an expense was for. The same list as the API's Category enum; expenses from before
// categories existed have none and are shown as "Other".

export type Category =
  | 'GROCERIES' | 'FOOD' | 'RENT' | 'UTILITIES' | 'TRANSPORT' | 'TRAVEL'
  | 'ENTERTAINMENT' | 'SHOPPING' | 'SUBSCRIPTIONS' | 'GIFTS' | 'OTHER';

export interface CategoryInfo { id: Category; label: string; emoji: string }

export const CATEGORIES: CategoryInfo[] = [
  { id: 'GROCERIES', label: 'Groceries', emoji: '🛒' },
  { id: 'FOOD', label: 'Eating out', emoji: '🍕' },
  { id: 'RENT', label: 'Rent', emoji: '🏠' },
  { id: 'UTILITIES', label: 'Bills', emoji: '💡' },
  { id: 'TRANSPORT', label: 'Transport', emoji: '🚆' },
  { id: 'TRAVEL', label: 'Travel', emoji: '✈️' },
  { id: 'ENTERTAINMENT', label: 'Going out', emoji: '🎬' },
  { id: 'SHOPPING', label: 'Shopping', emoji: '🛍️' },
  { id: 'SUBSCRIPTIONS', label: 'Subscriptions', emoji: '🔁' },
  { id: 'GIFTS', label: 'Gifts', emoji: '🎁' },
  { id: 'OTHER', label: 'Other', emoji: '🧾' },
];

export function categoryOf(id: string | null | undefined): CategoryInfo {
  return CATEGORIES.find((c) => c.id === id) ?? CATEGORIES[CATEGORIES.length - 1]!;
}

/**
 * Words that give a category away, checked in this order (so "Uber Eats" is food before "Uber" is
 * transport). Each must be a whole word ("db" matches "DB Vertrieb" but not "Feedback"); a
 * trailing * allows longer words ("pizz*" matches "Pizzeria").
 */
const KEYWORDS: [Category, string[]][] = [
  ['RENT', ['miete', 'hausverwaltung', 'kaution', 'nebenkosten', 'rent']],
  ['SUBSCRIPTIONS', ['netflix', 'spotify', 'disney+', 'amazon prime', 'prime video', 'youtube premium', 'apple.com', 'abo']],
  ['FOOD', ['uber eats', 'lieferando', 'wolt', 'pizz*', 'döner', 'doener', 'kebab', 'restaurant*', 'trattoria', 'sushi',
    'burger*', 'mcdonald*', 'kfc', 'café', 'cafe', 'kaffee', 'starbucks', 'bäcker*', 'baecker*', 'mensa',
    'imbiss', 'essen', 'dinner', 'lunch', 'brunch', 'frühstück']],
  ['GROCERIES', ['rewe', 'lidl', 'aldi*', 'edeka', 'netto', 'penny', 'kaufland', 'tegut', 'globus', 'alnatura', 'denns',
    'supermarkt', 'lebensmittel', 'einkauf', 'einkäufe', 'getränke*', 'getraenke*', 'späti', 'groceries']],
  ['UTILITIES', ['stadtwerke', 'strom', 'gas', 'wasser', 'internet', 'telekom', 'vodafone', 'o2', '1&1', 'rundfunk',
    'e.on', 'eon', 'vattenfall', 'handy']],
  ['TRAVEL', ['airbnb', 'hotel', 'hostel', 'booking.com', 'ryanair', 'eurowings', 'lufthansa', 'easyjet', 'flug',
    'urlaub', 'reise', 'trip']],
  ['TRANSPORT', ['db', 'deutsche bahn', 'bahn', 'flixbus', 'flixtrain', 'bvg', 'mvg', 'kvb', 'hvv', 'rmv', 'vrs',
    'deutschlandticket', 'ticket', 'taxi', 'uber', 'bolt', 'tanken', 'tankstelle', 'aral', 'shell', 'esso', 'parken',
    'sixt', 'share now', 'miles']],
  ['ENTERTAINMENT', ['kino', 'cinema', 'konzert', 'eventim', 'theater', 'museum', 'bowling', 'party', 'club', 'bar',
    'kneipe', 'festival']],
  ['GIFTS', ['geschenk*', 'geburtstag*', 'gift', 'birthday']],
  ['SHOPPING', ['amazon', 'ikea', 'mediamarkt', 'media markt', 'saturn', 'zalando', 'h&m', 'dm', 'rossmann', 'otto',
    'baumarkt', 'obi']],
];

const NOT_A_LETTER = '[^\\p{L}\\p{N}]';
const toPattern = (word: string) => (word.endsWith('*')
  ? escape(word.slice(0, -1))
  : `${escape(word)}(?=$|${NOT_A_LETTER})`);
const escape = (word: string) => word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const PATTERNS: [Category, RegExp][] = KEYWORDS.map(([category, words]) =>
  [category, new RegExp(`(?:^|${NOT_A_LETTER})(?:${words.map(toPattern).join('|')})`, 'u')]);

/** A suggestion for the description or bank payee, or null when nothing fits. */
export function guessCategory(...texts: (string | null | undefined)[]): Category | null {
  const text = texts.filter(Boolean).join(' ').toLowerCase();
  if (!text.trim()) return null;
  return PATTERNS.find(([, pattern]) => pattern.test(text))?.[0] ?? null;
}
