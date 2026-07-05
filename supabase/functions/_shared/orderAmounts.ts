// Pure order-amount logic for create-payment-intent. Deliberately free of any
// Deno / Stripe / Supabase / env dependency so it can be unit-tested in isolation
// (see orderAmounts.test.ts). Only standard globals (crypto, Date) are used, so
// this runs unchanged under both Deno (the Edge runtime) and Node (the tests).

export interface OrderAmountsInput {
  subtotal?: number;
  tax?: number;
  total?: number;
  currency?: string;
}

export interface OrderAmounts {
  subtotal: number;
  tax: number;
  total: number;
  currency: string;
}

// Currencies whose smallest unit is the whole unit (no cents). Amounts for these
// must NOT be multiplied by 100. Everything else is treated as 2-decimal.
export const ZERO_DECIMAL_CURRENCIES = new Set([
  'JPY',
  'KRW',
  'VND',
  'CLP',
  'XAF',
]);

// ⚠️  MVP ONLY — validates but ultimately TRUSTS client-provided amounts. Replace
// with a server-side price lookup before production. Until then, enforce basic
// integrity so obviously-malformed orders bounce rather than reaching Stripe.
export function deriveOrderAmounts(
  input: OrderAmountsInput,
): OrderAmounts | { error: string } {
  const { subtotal, tax, total } = input;
  const currency = (input.currency ?? 'USD').toUpperCase();

  if (
    typeof subtotal !== 'number' ||
    typeof tax !== 'number' ||
    typeof total !== 'number' ||
    !Number.isFinite(subtotal) ||
    !Number.isFinite(tax) ||
    !Number.isFinite(total)
  ) {
    return { error: 'subtotal, tax and total must be numbers' };
  }
  if (subtotal < 0 || tax < 0 || total < 0) {
    return { error: 'amounts must be non-negative' };
  }
  if (!/^[A-Z]{3}$/.test(currency)) {
    return { error: 'currency must be a 3-letter ISO-4217 code' };
  }
  if (total <= 0) {
    return { error: 'total must be greater than zero' };
  }
  // Integrity: total must equal subtotal + tax (tolerate float rounding to 1c).
  if (Math.abs(subtotal + tax - total) > 0.01) {
    return { error: 'total does not equal subtotal + tax' };
  }

  return { subtotal, tax, total, currency };
}

// Stripe expects integer amounts in the currency's smallest unit.
export function toMinorUnits(amount: number, currency: string): number {
  if (ZERO_DECIMAL_CURRENCIES.has(currency.toUpperCase())) {
    return Math.round(amount);
  }
  return Math.round(amount * 100);
}

export function generateOrderNumber(): string {
  const time = Date.now().toString(36).toUpperCase();
  const rand = crypto.randomUUID().slice(0, 8).toUpperCase();
  return `ORD-${time}-${rand}`;
}
