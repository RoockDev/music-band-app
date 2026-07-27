package com.banda.auth;

/** Token not found, wrong type, already used, or expired. Deliberately generic — never
 * carries the raw token value or reveals which of these was the cause. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("Invalid or expired token");
    }
}
