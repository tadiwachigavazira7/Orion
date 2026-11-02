"""Store enrollment codes: one reusable, rotatable secret per site.

Format: 20 characters of Crockford base32 (100 bits from `secrets`), shown to
operators as XXXXX-XXXXX-XXXXX-XXXXX so it can be typed on a PDT.

The code is reusable and human-typed, so it is stored only as an Argon2id
hash (slow, salted) via the CredentialHasher - unlike a random single-use
token, it could otherwise be attacked offline if the database leaked. It is
a different secret from any device credential and neither is derived from
the other.
"""

import secrets

CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
CODE_LENGTH = 20
GROUP_SIZE = 5

# Crockford decoding aliases: letters easily confused with digits.
_ALIASES = str.maketrans({"I": "1", "L": "1", "O": "0"})


def generate_code() -> str:
    """A new normalized code (no separators)."""
    return "".join(secrets.choice(CROCKFORD_ALPHABET) for _ in range(CODE_LENGTH))


def format_code(normalized: str) -> str:
    return "-".join(
        normalized[i : i + GROUP_SIZE] for i in range(0, len(normalized), GROUP_SIZE)
    )


def normalize_code(submitted: str) -> str:
    """Strip, uppercase, drop hyphens/spaces, and apply Crockford aliases
    (I/L -> 1, O -> 0). The hash is always computed over this form."""
    cleaned = "".join(ch for ch in submitted.strip().upper() if ch not in "- \t")
    return cleaned.translate(_ALIASES)
