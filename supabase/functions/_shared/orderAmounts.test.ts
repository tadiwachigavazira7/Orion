// Unit tests for the pure order-amount logic. Runs under Node's built-in test
// runner (no Deno / Stripe / Supabase needed):
//   node --test supabase/functions/_shared/orderAmounts.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  deriveOrderAmounts,
  toMinorUnits,
  generateOrderNumber,
} from './orderAmounts.ts';

// Narrowing helper: fail loudly if we got an error object instead of amounts.
function expectAmounts(result: ReturnType<typeof deriveOrderAmounts>) {
  assert.ok(!('error' in result), `expected amounts, got error: ${JSON.stringify(result)}`);
  return result;
}

test('deriveOrderAmounts: accepts a valid order and defaults currency to USD', () => {
  const result = expectAmounts(deriveOrderAmounts({ subtotal: 10, tax: 2, total: 12 }));
  assert.deepEqual(result, { subtotal: 10, tax: 2, total: 12, currency: 'USD' });
});

test('deriveOrderAmounts: uppercases the provided currency', () => {
  const result = expectAmounts(deriveOrderAmounts({ subtotal: 5, tax: 0, total: 5, currency: 'eur' }));
  assert.equal(result.currency, 'EUR');
});

test('deriveOrderAmounts: allows zero tax', () => {
  const result = expectAmounts(deriveOrderAmounts({ subtotal: 5, tax: 0, total: 5 }));
  assert.equal(result.tax, 0);
});

test('deriveOrderAmounts: tolerates sub-cent float rounding', () => {
  // 0.1 + 0.2 = 0.30000000000000004 in IEEE-754; must still pass.
  const result = deriveOrderAmounts({ subtotal: 0.1, tax: 0.2, total: 0.3 });
  assert.ok(!('error' in result));
});

test('deriveOrderAmounts: rejects missing amounts', () => {
  const result = deriveOrderAmounts({ subtotal: 10, tax: 2 });
  assert.deepEqual(result, { error: 'subtotal, tax and total must be numbers' });
});

test('deriveOrderAmounts: rejects non-numeric amounts', () => {
  // Simulate a manipulated client body sending strings.
  const result = deriveOrderAmounts({ subtotal: '10' as unknown as number, tax: 2, total: 12 });
  assert.deepEqual(result, { error: 'subtotal, tax and total must be numbers' });
});

test('deriveOrderAmounts: rejects NaN / Infinity', () => {
  assert.ok('error' in deriveOrderAmounts({ subtotal: NaN, tax: 0, total: NaN }));
  assert.ok('error' in deriveOrderAmounts({ subtotal: Infinity, tax: 0, total: Infinity }));
});

test('deriveOrderAmounts: rejects negative amounts', () => {
  const result = deriveOrderAmounts({ subtotal: -1, tax: 0, total: -1 });
  assert.deepEqual(result, { error: 'amounts must be non-negative' });
});

test('deriveOrderAmounts: rejects a zero total', () => {
  const result = deriveOrderAmounts({ subtotal: 0, tax: 0, total: 0 });
  assert.deepEqual(result, { error: 'total must be greater than zero' });
});

test('deriveOrderAmounts: rejects a malformed currency code', () => {
  const result = deriveOrderAmounts({ subtotal: 5, tax: 0, total: 5, currency: 'US' });
  assert.deepEqual(result, { error: 'currency must be a 3-letter ISO-4217 code' });
});

test('deriveOrderAmounts: rejects total != subtotal + tax (integrity guard)', () => {
  // The security-relevant case: client claims a total that does not match parts.
  const result = deriveOrderAmounts({ subtotal: 100, tax: 10, total: 1 });
  assert.deepEqual(result, { error: 'total does not equal subtotal + tax' });
});

test('toMinorUnits: converts 2-decimal currencies to cents', () => {
  assert.equal(toMinorUnits(12.34, 'USD'), 1234);
  assert.equal(toMinorUnits(0.99, 'usd'), 99);
});

test('toMinorUnits: rounds to the nearest cent', () => {
  assert.equal(toMinorUnits(1.005, 'USD'), 100); // 1.005*100 = 100.499.. → 100
  assert.equal(toMinorUnits(1.006, 'USD'), 101);
});

test('toMinorUnits: leaves zero-decimal currencies unmultiplied', () => {
  assert.equal(toMinorUnits(500, 'JPY'), 500);
  assert.equal(toMinorUnits(500.4, 'jpy'), 500);
});

test('generateOrderNumber: matches the ORD-<time>-<rand> format', () => {
  assert.match(generateOrderNumber(), /^ORD-[0-9A-Z]+-[0-9A-Z]{8}$/);
});

test('generateOrderNumber: produces distinct values', () => {
  const values = new Set(Array.from({ length: 1000 }, () => generateOrderNumber()));
  assert.equal(values.size, 1000);
});
