package com.metropulse.auth.domain;

/**
 * Thrown when a login fails.
 *
 * <p>Deliberately one exception for every cause - unknown account, wrong password, deactivated user.
 * Distinguishing them in the response tells an attacker which email addresses are real.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password.");
    }
}
