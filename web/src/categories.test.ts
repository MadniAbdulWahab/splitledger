import { describe, expect, it } from 'vitest';
import { categoryOf, guessCategory } from './categories';

describe('guessCategory', () => {
  it.each([
    // Payees from the sample statements.
    ['REWE Markt Bonn-Poppelsdorf', 'GROCERIES'],
    ['EDEKA Center Bonn', 'GROCERIES'],
    ['Lidl Bonn', 'GROCERIES'],
    ['Getränke Hoffmann KG', 'GROCERIES'],
    ['Bäckerei Müller', 'FOOD'],
    ['Cafe Mokka', 'FOOD'],
    ['Pizzeria Napoli', 'FOOD'],
    ['DB Vertrieb GmbH', 'TRANSPORT'],
    ['Hausverwaltung Rheinblick GmbH', 'RENT'],
    ['Stadtwerke Bonn Energie', 'UTILITIES'],
    // Typed descriptions.
    ['Uber Eats', 'FOOD'],
    ['Uber to the airport', 'TRANSPORT'],
    ['Netflix', 'SUBSCRIPTIONS'],
    ['Geschenk für Jonas', 'GIFTS'],
    ['Airbnb Lisbon', 'TRAVEL'],
    ['Kino', 'ENTERTAINMENT'],
    ['IKEA Shelf', 'SHOPPING'],
  ])('%s is %s', (text, category) => {
    expect(guessCategory(text)).toBe(category);
  });

  it('only matches at the start of a word', () => {
    expect(guessCategory('Feedback session')).toBeNull(); // not "db"
    expect(guessCategory('Ottoman')).toBeNull(); // not "otto"
  });

  it('combines the description with the bank payee', () => {
    expect(guessCategory('Weekly shop', 'REWE Markt')).toBe('GROCERIES');
    expect(guessCategory('', null, undefined)).toBeNull();
  });
});

describe('categoryOf', () => {
  it('shows expenses without a category as Other', () => {
    expect(categoryOf(null).id).toBe('OTHER');
    expect(categoryOf('RENT').label).toBe('Rent');
  });
});
