import { supabase } from '../../../lib/supabase';

// Mirrors the columns selected from public.payment_methods.
// Sensitive fields (raw PAN, full expiry secret, CVV) never exist here by
// design — only the processor token reference and safe display fields.
export interface PaymentMethod {
  id: string;
  provider: string;
  card_brand: string | null;
  card_last4: string | null;
  exp_month: number | null;
  exp_year: number | null;
  name_on_card: string | null;
  billing_zip: string | null;
  is_default: boolean;
  created_at: string;
  updated_at: string;
}

// Shape consumed by the <SavedPaymentCard /> component.
export interface SavedCardView {
  id: string;
  cardBrand: string;
  last4: string;
  expDate: string;
}

// Narrowed column list — we never request provider_customer_id or
// provider_payment_method_id (the charge tokens) into the UI layer.
const PAYMENT_METHOD_COLUMNS = `
  id,
  provider,
  card_brand,
  card_last4,
  exp_month,
  exp_year,
  name_on_card,
  billing_zip,
  is_default,
  created_at,
  updated_at
`;

// Fetch the signed-in user's saved cards. RLS scopes rows to auth.uid(),
// so no explicit user_id filter is needed.
export async function fetchPaymentMethods(): Promise<PaymentMethod[]> {
  const { data, error } = await supabase
    .from('payment_methods')
    .select(PAYMENT_METHOD_COLUMNS)
    .order('is_default', { ascending: false })
    .order('created_at', { ascending: true });

  if (error) {
    console.warn('[paymentMethods] fetch failed:', error.message);
    return [];
  }
  return (data as PaymentMethod[]) ?? [];
}

export function toSavedCardView(pm: PaymentMethod): SavedCardView {
  return {
    id: pm.id,
    cardBrand: pm.card_brand ?? 'Card',
    last4: pm.card_last4 ?? '••••',
    expDate:
      pm.exp_month && pm.exp_year
        ? `${String(pm.exp_month).padStart(2, '0')}/${pm.exp_year}`
        : 'XX/XXXX',
  };
}
