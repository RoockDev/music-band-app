package com.banda.users;

/**
 * An actor attempted to {@code edit}/{@code deactivate} their own {@link UserAccount}
 * through {@link UserService}. Rejected unconditionally, regardless of which permissions
 * the actor holds — no actor may edit or deactivate their own account via this admin-panel
 * surface, full stop, not even an ADMIN holding every permission toggle.
 */
public class SelfTargetNotAllowedException extends RuntimeException {

    public SelfTargetNotAllowedException() {
        super("An actor cannot edit or deactivate their own account");
    }
}
