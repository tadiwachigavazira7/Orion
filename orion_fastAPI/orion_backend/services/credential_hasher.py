"""Credential hashing/verification, isolated behind a small interface.

This is the initial shared-secret implementation (Argon2id). Orion may later
migrate to a public-key / challenge-response design (see CLAUDE.md §"Credential
architecture") — callers depend only on the CredentialHasher protocol, so that
migration only requires a new implementation of this interface, not changes to
the API routes or the device service.
"""

from typing import Protocol

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError


class CredentialHasher(Protocol):
    def hash(self, credential: str) -> str: ...

    def verify(self, credential: str, credential_hash: str) -> bool: ...


class Argon2CredentialHasher:
    """Argon2id hashing (argon2-cffi's PasswordHasher defaults to Argon2id)."""

    def __init__(self) -> None:
        self._hasher = PasswordHasher()

    def hash(self, credential: str) -> str:
        return self._hasher.hash(credential)

    def verify(self, credential: str, credential_hash: str) -> bool:
        try:
            self._hasher.verify(credential_hash, credential)
            return True
        except VerifyMismatchError:
            return False
