export function parseIdList(value: string): number[] | null {
  if (!value.trim()) {
    return [];
  }

  const ids = value.split(',').map((entry) => Number(entry.trim()));
  if (ids.some((id) => !Number.isInteger(id) || id <= 0)) {
    return null;
  }

  return [...new Set(ids)];
}

export function toLocalDateTime(value: string): string {
  const date = new Date(value);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}
