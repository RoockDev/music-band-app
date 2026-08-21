package com.banda.auth;

/**
 * Internal signal used to roll back reset-token issuance when SMTP delivery fails.
 * The public controller deliberately converts it to the same neutral response returned
 * for every password-reset request.
 */
class PasswordResetDeliveryException extends RuntimeException {
}
