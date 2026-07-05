// Unit tests for the pure payments client-service logic. Runs under Node's
// built-in test runner (no Supabase / React Native needed):
//   node --test "src/**/*.test.ts"
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isValidCheckoutAmount, unwrapInvokeResult } from '../paymentsCore.ts';

test('unwrapInvokeResult: returns data on success', () => {
  const payload = { clientSecret: 'pi_secret' };
  assert.deepEqual(unwrapInvokeResult('fn', payload, null), payload);
});

test('unwrapInvokeResult: throws with the function name on error', () => {
  assert.throws(
    () => unwrapInvokeResult('create-payment-intent', null, { message: 'card declined' }),
    /create-payment-intent failed: card declined/,
  );
});

test('unwrapInvokeResult: throws when data is null but no error given', () => {
  assert.throws(
    () => unwrapInvokeResult('create-setup-intent', null, null),
    /create-setup-intent returned no data/,
  );
});

test('isValidCheckoutAmount: accepts a consistent amount', () => {
  assert.equal(isValidCheckoutAmount(10, 2, 12), true);
});

test('isValidCheckoutAmount: accepts zero tax', () => {
  assert.equal(isValidCheckoutAmount(5, 0, 5), true);
});

test('isValidCheckoutAmount: tolerates sub-cent float rounding', () => {
  assert.equal(isValidCheckoutAmount(0.1, 0.2, 0.3), true);
});

test('isValidCheckoutAmount: rejects a zero total', () => {
  assert.equal(isValidCheckoutAmount(0, 0, 0), false);
});

test('isValidCheckoutAmount: rejects negative parts', () => {
  assert.equal(isValidCheckoutAmount(-1, 0, 5), false);
});

test('isValidCheckoutAmount: rejects a mismatched total', () => {
  assert.equal(isValidCheckoutAmount(100, 10, 1), false);
});

test('isValidCheckoutAmount: rejects non-finite numbers', () => {
  assert.equal(isValidCheckoutAmount(NaN, 0, NaN), false);
  assert.equal(isValidCheckoutAmount(Infinity, 0, Infinity), false);
});
