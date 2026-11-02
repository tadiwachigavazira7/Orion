"""Input validation for operator-supplied identifiers.

The HTTP API validates its inputs with Pydantic (schemas.py); operator
commands come in through the CLI, so the services validate them here before
anything is written.
"""

from orion_backend.errors import OperatorInputError

IDENTIFIER_MAX_LENGTH = 128
NAME_MAX_LENGTH = 255


def _require_text(field: str, value: str, max_length: int) -> str:
    if not value or not value.strip():
        raise OperatorInputError(f"{field} must not be empty.")
    if value != value.strip():
        raise OperatorInputError(f"{field} must not have leading or trailing whitespace.")
    if len(value) > max_length:
        raise OperatorInputError(f"{field} must be at most {max_length} characters.")
    if any(not ch.isprintable() for ch in value):
        raise OperatorInputError(f"{field} must not contain control characters.")
    return value


def require_identifier(field: str, value: str) -> str:
    return _require_text(field, value, IDENTIFIER_MAX_LENGTH)


def require_name(field: str, value: str) -> str:
    return _require_text(field, value, NAME_MAX_LENGTH)
