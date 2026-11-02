from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Environment = Literal["development", "test", "production"]


class Settings(BaseSettings):
    """Runtime configuration, sourced from environment variables / .env.

    No secrets or connection strings are hardcoded — see .env.example.
    """

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    cors_origins: str = ""

    # How long a device may keep running Orion without reaching
    # POST /enrolled_devices/{id}/verify. The server decides this and returns
    # it as offline_valid_until; the device never extends it on its own.
    offline_authorization_hours: int = Field(default=24, gt=0)

    # DB-backed rate limiting of failed store-code attempts (sliding window).
    # Per (site, client IP), plus a per-site ceiling that bounds distributed
    # guessing. Client IP is the direct peer address; proxy headers are not
    # trusted (see OPERATIONS.md).
    enrollment_rate_limit_window_minutes: int = Field(default=15, gt=0)
    enrollment_rate_limit_max_failures_per_client: int = Field(default=5, gt=0)
    enrollment_rate_limit_max_failures_per_site: int = Field(default=50, gt=0)

    # Deliberately has no default: tooling that is only safe in development
    # (scripts/seed_dev_data.py) refuses to run unless this is explicitly
    # "development".
    orion_environment: Environment | None = None

    @field_validator("orion_environment", mode="before")
    @classmethod
    def _empty_environment_is_unset(cls, value: object) -> object:
        return None if isinstance(value, str) and value.strip() == "" else value

    @field_validator("cors_origins")
    @classmethod
    def _explicit_origins_only(cls, value: str) -> str:
        if any(origin.strip() == "*" for origin in value.split(",")):
            raise ValueError("CORS_ORIGINS must list explicit origins; '*' is not allowed.")
        return value

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
