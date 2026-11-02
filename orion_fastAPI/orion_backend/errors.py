"""Domain-level errors for device enrollment/verification and operator actions.

Route handlers never construct HTTP responses for these cases directly —
they let the service layer raise one of these, and the FastAPI exception
handlers registered in main.py translate them into a consistent JSON shape:
{"detail": ..., "error_code": ...}. This keeps status-code decisions in one
place instead of scattered across route handlers.

The operator CLI (orion_backend/cli.py) uses the same types: it prints
error_code + message and exits nonzero. No message ever contains a
device credential, a store enrollment code, or a hash of either.
"""


class OrionDeviceError(Exception):
    error_code: str = "device_error"
    http_status: int = 500

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message

    @property
    def headers(self) -> dict[str, str] | None:
        """Extra HTTP response headers (e.g. Retry-After)."""
        return None


class DeviceAlreadyEnrolledError(OrionDeviceError):
    error_code = "device_already_enrolled"
    http_status = 409

    def __init__(self, device_id: str):
        super().__init__(f"Device '{device_id}' is already enrolled.")


class DeviceNotFoundError(OrionDeviceError):
    error_code = "device_not_found"
    http_status = 404

    def __init__(self, device_id: str):
        super().__init__(f"Device '{device_id}' was not found.")


class DeviceRevokedError(OrionDeviceError):
    error_code = "device_revoked"
    http_status = 403

    def __init__(self, device_id: str):
        super().__init__(f"Device '{device_id}' has been revoked.")


class InvalidCredentialError(OrionDeviceError):
    error_code = "invalid_credential"
    http_status = 401

    def __init__(self, device_id: str):
        super().__init__(f"Credential for device '{device_id}' did not verify.")


class OrganizationNotFoundError(OrionDeviceError):
    error_code = "organization_not_found"
    http_status = 404

    def __init__(self, organization_code: str):
        super().__init__(f"Organization '{organization_code}' was not found.")


class SiteNotFoundError(OrionDeviceError):
    error_code = "site_not_found"
    http_status = 404

    def __init__(self, site_code: str):
        super().__init__(f"Site '{site_code}' was not found.")


class SiteOrganizationMismatchError(OrionDeviceError):
    """The site code exists, but not under the given organization.

    Kept distinct from SiteNotFoundError - not a credential-secrecy concern
    (organization/site codes are not secrets), so the more specific error is
    safe to surface and independently testable.
    """

    error_code = "site_organization_mismatch"
    http_status = 404

    def __init__(self, site_code: str, organization_code: str):
        super().__init__(
            f"Site '{site_code}' does not belong to organization '{organization_code}'."
        )


class InvalidEnrollmentAuthorizationError(OrionDeviceError):
    """The store enrollment code is unusable for this request.

    Deliberately one generic error for every cause (wrong code, another
    store's code, no code configured for the site, code rotated mid-request,
    device bound to another site) so a caller cannot probe which check failed.
    """

    error_code = "invalid_enrollment_authorization"
    http_status = 403

    def __init__(self) -> None:
        super().__init__("Enrollment authorization code is invalid.")


class SubscriptionInactiveError(OrionDeviceError):
    """The site's subscription does not allow new enrollments (INACTIVE or
    SUSPENDED). Only raised after the store code verified, so it never
    reveals subscription state to a caller without the code."""

    error_code = "subscription_inactive"
    http_status = 403

    def __init__(self) -> None:
        super().__init__("This site's subscription does not allow new device enrollments.")


class DeviceLimitReachedError(OrionDeviceError):
    error_code = "device_limit_reached"
    http_status = 409

    def __init__(self) -> None:
        super().__init__("This site has reached its authorized device limit.")


class EnrollmentRateLimitedError(OrionDeviceError):
    error_code = "enrollment_rate_limited"
    http_status = 429

    def __init__(self, retry_after_seconds: int):
        super().__init__("Too many failed enrollment attempts; retry later.")
        self.retry_after_seconds = retry_after_seconds

    @property
    def headers(self) -> dict[str, str] | None:
        return {"Retry-After": str(self.retry_after_seconds)}


# --- Operator (CLI-only) errors -------------------------------------------
# These are never raised by an HTTP route; http_status is set for
# completeness so the shared exception handler stays total.


class OperatorInputError(OrionDeviceError):
    error_code = "invalid_input"
    http_status = 422


class OrganizationConflictError(OrionDeviceError):
    error_code = "organization_conflict"
    http_status = 409

    def __init__(self, organization_code: str):
        super().__init__(
            f"Organization '{organization_code}' already exists with a different name; "
            "refusing to change it."
        )


class SiteAlreadyExistsError(OrionDeviceError):
    error_code = "site_already_exists"
    http_status = 409

    def __init__(self, site_code: str, organization_code: str):
        super().__init__(
            f"Site '{site_code}' already exists in organization '{organization_code}'."
        )


class SubscriptionNotFoundError(OrionDeviceError):
    """Every site is created with a subscription (and the migration backfills
    existing ones); a missing row is a data problem to fix deliberately."""

    error_code = "subscription_not_found"
    http_status = 409

    def __init__(self, site_code: str):
        super().__init__(f"Site '{site_code}' has no subscription record.")


class CapacityBelowActiveDevicesError(OrionDeviceError):
    error_code = "capacity_below_active_devices"
    http_status = 409

    def __init__(self, requested_total: int, active_devices: int):
        super().__init__(
            f"Requested capacity {requested_total} is below the {active_devices} ACTIVE "
            "device(s) at this site; revoke or reset excess devices first."
        )


class InvalidDeviceTransitionError(OrionDeviceError):
    error_code = "invalid_device_transition"
    http_status = 409

    def __init__(self, device_id: str, action: str, status: str):
        super().__init__(f"Cannot {action} device '{device_id}' while it is {status}.")


class ConcurrentOperationError(OrionDeviceError):
    error_code = "concurrent_operation"
    http_status = 409

    def __init__(self, what: str):
        super().__init__(f"A concurrent change conflicted with this operation ({what}); retry.")
