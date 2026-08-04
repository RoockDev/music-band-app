package com.banda.users;

/** A create/edit request used an email already held by another {@link UserAccount}. */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("Email already in use");
    }
}
