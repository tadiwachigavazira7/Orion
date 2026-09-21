from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration, sourced from environment variables / .env.

    No secrets or connection strings are hardcoded — see .env.example.
    """

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    cors_origins: str = ""
    # Gates the /admin/* provisioning endpoints (see api/provisioning.py).
    # Required, never hardcoded - must be a long random secret in real
    # deployments and must never be committed. min_length guards against an
    # operator setting ADMIN_API_KEY= (empty), which would otherwise let an
    # empty X-Admin-Api-Key header authenticate.
    admin_api_key: str = Field(min_length=16)

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
