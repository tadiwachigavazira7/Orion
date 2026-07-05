import { useCallback, useState } from 'react';
import {
  CheckoutInput,
  createPaymentIntent,
  createSetupIntent,
  PaymentIntentResult,
  SetupIntentResult,
} from './payments';

// Wraps the payments service with loading/error state for UI consumption, so
// screens stay free of business logic. Both actions return their result on
// success or null on failure (with `error` populated).
//
// NOTE: actually presenting the Stripe PaymentSheet with the returned secrets
// requires @stripe/stripe-react-native, which is NOT yet installed. Once it is,
// call presentPaymentSheet / confirmSetupIntent inside these handlers. Until
// then this hook only performs the server side of each flow.
export function usePayments() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const startSaveCard = useCallback(async (): Promise<SetupIntentResult | null> => {
    setLoading(true);
    setError(null);
    try {
      return await createSetupIntent();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Failed to start card setup');
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  const startCheckout = useCallback(
    async (input: CheckoutInput): Promise<PaymentIntentResult | null> => {
      setLoading(true);
      setError(null);
      try {
        return await createPaymentIntent(input);
      } catch (caught) {
        setError(caught instanceof Error ? caught.message : 'Failed to start checkout');
        return null;
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  return { loading, error, startSaveCard, startCheckout };
}
