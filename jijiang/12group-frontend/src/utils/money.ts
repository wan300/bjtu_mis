export function money(value?: number | string) {
  const num = Number(value || 0);
  return num.toFixed(num % 1 === 0 ? 0 : 2);
}
