package com.banda.users;

/**
 * A create/edit request violates a domain rule — currently only the Section 3 minor
 * enforcement ("guardian contact + consent flag required, enforced"). The message is safe
 * to return to the caller as-is: it never echoes back submitted PII, only which rule was
 * violated.
 */
public class InvalidUserDataException extends RuntimeException {

    public InvalidUserDataException(String message) {
        super(message);
    }
}
