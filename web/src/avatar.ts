// Every member keeps one colour, taken from the euro banknotes (5 € grey to 500 € purple), so a
// person is recognisable at a glance in every list, chart and on every device.

export const NOTE_COLOURS = ['#7d8790', '#c9504a', '#3d6fc4', '#d9822b', '#3f9b5a', '#b8952a', '#8a5bb8'] as const;

/** Member ids are stable, so the colour follows the person, not their position in a list. */
export function memberColour(memberId: number): string {
  return NOTE_COLOURS[((memberId % NOTE_COLOURS.length) + NOTE_COLOURS.length) % NOTE_COLOURS.length]!;
}

/** "Anna Beispiel" -> "AB", "ben" -> "B". */
export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]!.toUpperCase()).join('') || '?';
}
