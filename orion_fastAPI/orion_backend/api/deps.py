import secrets
from collections.abc import AsyncIterator

from fastapi import Header, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.config import get_settings
from orion_backend.services.credential_hasher import Argon2CredentialHasher, CredentialHasher

# One hasher instance is enough: PasswordHasher itself holds no mutable
# per-call state, and constructing it repeatedly would re-read Argon2
# parameters on every request for no benefit.
_hasher = Argon2CredentialHasher()


async def get_db(request: Request) -> AsyncIterator[AsyncSession]:
    sessionmaker = request.app.state.sessionmaker
    async with sessionmaker() as session:
        yield session


def get_credential_hasher() -> CredentialHasher:
    return _hasher


def require_admin_api_key(x_admin_api_key: str | None = Header(default=None)) -> None:
    """Gate Orion's internal /admin/* provisioning endpoints.

    Deliberately separate from the Android enrollment/verification/devices
    endpoints, which stay unauthenticated - see CLAUDE.md. Comparison uses
    secrets.compare_digest to avoid a timing side channel on the key.
    """
    expected = get_settings().admin_api_key
    if x_admin_api_key is None or not secrets.compare_digest(x_admin_api_key, expected):
        raise HTTPException(status_code=401, detail="Invalid or missing admin API key")
