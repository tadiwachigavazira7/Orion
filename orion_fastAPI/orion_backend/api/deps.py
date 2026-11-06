import secrets
from collections.abc import AsyncIterator

from fastapi import Header, Request
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.config import get_settings
from orion_backend.errors import AdminApiNotConfiguredError, InvalidAdminKeyError
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


def require_admin_key(x_admin_key: str | None = Header(default=None)) -> None:
    expected = get_settings().admin_api_key
    if not expected:
        raise AdminApiNotConfiguredError()
    if x_admin_key is None or not secrets.compare_digest(
        x_admin_key.encode(), expected.encode()
    ):
        raise InvalidAdminKeyError()
