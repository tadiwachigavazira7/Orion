// Pure helpers for the payments client service. Kept free of the Supabase/React
// Native import chain so they can be unit-tested directly under Node
// (see __tests__/paymentsCore.test.ts).

export interface InvokeError {
  message: string;
}

// Normalizes a supabase.functions.invoke() result into either the payload or a
// thrown Error. Edge Functions return { data, error }; a non-2xx response sets
// error and usually nulls data, so both cases are handled explicitly rather than
// letting a null payload flow silently into the UI.
export function unwrapInvokeResult<T>(
  functionName: string,
  data: T | null,
  error: InvokeError | null,
): T {
  if (error) {
    throw new Error(`${functionName} failed: ${error.message}`);
  }
  if (data === null || data === undefined) {
    throw new Error(`${functionName} returned no data`);
  }
  return data;
}

// Client-side pre-flight validation of a checkout amount. The Edge Function is
// the authoritative validator (never trust the client), but catching obvious
// mistakes here avoids a pointless round-trip and gives faster UI feedback.
export function isValidCheckoutAmount(subtotal: number, tax: number, total: number): boolean {
  const allNumbers = [subtotal, tax, total].every((n) => typeof n === 'number' && Number.isFinite(n));
  if (!allNumbers) return false;
  if (subtotal < 0 || tax < 0 || total <= 0) return false;
  return Math.abs(subtotal + tax - total) <= 0.01;
}
