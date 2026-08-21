import { parseIdList, toLocalDateTime } from './admin-form.utils';

describe('parseIdList', () => {
  it('parses unique positive IDs and accepts an empty scope', () => {
    expect(parseIdList('2, 5, 2')).toEqual([2, 5]);
    expect(parseIdList('')).toEqual([]);
  });

  it('rejects malformed and non-positive IDs', () => {
    expect(parseIdList('2, unknown')).toBeNull();
    expect(parseIdList('0, 2')).toBeNull();
  });
});

describe('toLocalDateTime', () => {
  it('formats an instant for a datetime-local input', () => {
    const instant = '2026-08-21T10:30:00Z';
    const date = new Date(instant);
    const expected = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
      .toISOString()
      .slice(0, 16);

    expect(toLocalDateTime(instant)).toBe(expected);
  });
});
