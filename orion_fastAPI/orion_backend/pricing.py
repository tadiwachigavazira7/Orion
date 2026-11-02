"""Informational pricing - display only.

Used solely to show an estimate in operator output (e.g. inspect-site).
No billing or payment is performed anywhere in Orion, and authorization,
enrollment and capacity logic must never import this module (enforced by a
test). Capacity comes only from site_subscriptions, never from prices.
"""

from dataclasses import dataclass
from decimal import Decimal

CURRENCY = "USD"
BASE_MONTHLY_PRICE_PER_STORE = Decimal("400")
INCLUDED_SLOTS_IN_BASE_PRICE = 6
ADDITIONAL_SLOT_MONTHLY_PRICE = Decimal("50")
ESTIMATE_LABEL = "estimate - no billing performed"


@dataclass(frozen=True)
class MonthlyEstimate:
    base: Decimal
    additional_slots: int
    additional_cost: Decimal
    total: Decimal
    currency: str = CURRENCY
    label: str = ESTIMATE_LABEL


def estimate_monthly(additional_device_slots: int) -> MonthlyEstimate:
    if additional_device_slots < 0:
        raise ValueError("additional_device_slots must be >= 0")
    additional_cost = ADDITIONAL_SLOT_MONTHLY_PRICE * additional_device_slots
    return MonthlyEstimate(
        base=BASE_MONTHLY_PRICE_PER_STORE,
        additional_slots=additional_device_slots,
        additional_cost=additional_cost,
        total=BASE_MONTHLY_PRICE_PER_STORE + additional_cost,
    )
