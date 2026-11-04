"""Domain-level errors for device enrollment/verification/revocation.

Route handlers never construct HTTP responses for these cases directly —
they let the service layer raise one of these, and the FastAPI exception
handlers registered in main.py translate them into a consistent JSON shape:
{"detail": ..., "error_code": ...}. This keeps status-code decisions in one
place instead of scattered across route handlers.
"""


class OrionDeviceError(Exception):
    error_code: str = "device_error"
    http_status: int = 500

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class DeviceAlreadyEnrolledError(OrionDeviceError):
    error_code = "device_already_enrolled"
    http_status = 409

    def __init__(self, device_id: str):
        super().__init__(f"Device '{device_id}' is already enrolled.")


class DeviceNotFoundError(OrionDeviceError):
    error_code = "device_not_found"
    http_status = 404

    def __init__(self, device_id: str):
        super().__init__(f"Device '{device_id}' is not enrolled.")


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


class SiteCapacityExceededError(OrionDeviceError):
    error_code = "site_capacity_exceeded"
    http_status = 409

    def __init__(self, capacity: int):
        super().__init__(f"Site is at its device capacity of {capacity}.")


class CapacityBelowOccupancyError(OrionDeviceError):
    error_code = "capacity_below_active_devices"
    http_status = 409

    def __init__(self, max_devices: int, active_devices: int):
        super().__init__(
            f"max_devices {max_devices} would be below the {active_devices} currently active "
            "devices; revoke devices first."
        )


class InvalidAdminKeyError(OrionDeviceError):
    error_code = "invalid_admin_key"
    http_status = 401

    def __init__(self):
        super().__init__("Missing or invalid admin key.")


class AdminApiNotConfiguredError(OrionDeviceError):
    error_code = "admin_api_not_configured"
    http_status = 503

    def __init__(self):
        super().__init__("Admin API is not configured on this server.")


class InvalidEnrollmentCodeError(OrionDeviceError):
    """Wrong code, or the site has no enrollment code provisioned (fail closed).
    Deliberately indistinguishable to the client."""

    error_code = "invalid_enrollment_code"
    http_status = 403

    def __init__(self):
        super().__init__("Invalid enrollment code.")


class SubscriptionNotActiveError(OrionDeviceError):
    """One generic error for every not-entitled case (none/pending/suspended/
    expired/canceled/period over) so billing detail is not leaked."""

    error_code = "subscription_not_active"
    http_status = 403

    def __init__(self):
        super().__init__("This site is not currently authorized to use Orion.")


class SubscriptionNotFoundError(OrionDeviceError):
    error_code = "subscription_not_found"
    http_status = 404

    def __init__(self, site_code: str):
        super().__init__(f"Site '{site_code}' has no subscription.")


class SubscriptionAlreadyExistsError(OrionDeviceError):
    error_code = "subscription_already_exists"
    http_status = 409

    def __init__(self, site_code: str):
        super().__init__(f"Site '{site_code}' already has a subscription.")


class InvalidSubscriptionInputError(OrionDeviceError):
    error_code = "invalid_subscription_input"
    http_status = 422

    def __init__(self, message: str):
        super().__init__(message)
