import { supabase } from '../../../lib/supabase';

// Mirrors the columns selected from public.purchases. Users have read-only
// access to their own rows (RLS: select_own_purchases); the table is written
// only by a trusted backend after payment confirmation.
export type PurchaseStatus =
  | 'pending'
  | 'processing'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'refunded';

export interface Purchase {
  id: string;
  order_number: string;
  status: PurchaseStatus;
  subtotal: string;
  tax: string;
  total: string;
  currency: string;
  purchased_at: string;
  created_at: string;
  updated_at: string;
}

// Shape consumed by the purchases tab UI.
export interface PurchaseView {
  id: string;
  orderNumber: string;
  status: PurchaseStatus;
  total: string;
  purchasedAt: string;
}

// Narrowed column list — we never pull payment_method_id or user_id into the
// UI layer; they aren't displayed and aren't needed here.
const PURCHASE_COLUMNS = `
  id,
  order_number,
  status,
  subtotal,
  tax,
  total,
  currency,
  purchased_at,
  created_at,
  updated_at
`;

// Fetch the signed-in user's purchase history. RLS scopes rows to auth.uid(),
// so no explicit user_id filter is needed. Newest purchases first, matching the
// idx_purchases_user_purchased_at index.
export async function fetchPurchases(): Promise<Purchase[]> {
  const { data, error } = await supabase
    .from('purchases')
    .select(PURCHASE_COLUMNS)
    .order('purchased_at', { ascending: false });

  if (error) {
    console.warn('[purchases] fetch failed:', error.message);
    return [];
  }
  return (data as Purchase[]) ?? [];
}

export function toPurchaseView(purchase: Purchase): PurchaseView {
  return {
    id: purchase.id,
    orderNumber: purchase.order_number,
    status: purchase.status,
    total: formatMoney(purchase.total, purchase.currency),
    purchasedAt: formatDate(purchase.purchased_at),
  };
}

// numeric(12,2) arrives from Supabase as a string; format for display without
// losing precision to floating point.
function formatMoney(amount: string, currency: string): string {
  const value = Number(amount);
  if (Number.isNaN(value)) {
    return `${amount} ${currency}`;
  }
  return `${value.toFixed(2)} ${currency}`;
}

function formatDate(timestamp: string): string {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) {
    return timestamp;
  }
  return date.toLocaleDateString();
}
