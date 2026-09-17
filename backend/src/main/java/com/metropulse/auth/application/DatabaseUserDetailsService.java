package com.metropulse.auth.application;

import com.metropulse.auth.domain.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads operators for HTTP Basic authentication.
 *
 * <p>Basic auth exists for curl, smoke tests and the development tooling. It deliberately uses the
 * same accounts, the same password hashes and the same roles as token authentication: a second set of
 * credentials with its own permissions would be a second security model to keep correct, and the one
 * that gets forgotten is always the one that matters.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<UserDetails> users = jdbcTemplate.query("""
                SELECT email, password_hash, role, active
                FROM app_user
                WHERE lower(email) = lower(?)
                """,
                (rs, rowNum) -> User.withUsername(rs.getString("email"))
                        .password(rs.getString("password_hash"))
                        .authorities(UserRole.valueOf(rs.getString("role")).authority())
                        .disabled(!rs.getBoolean("active"))
                        .build(),
                username);

        if (users.isEmpty()) {
            throw new UsernameNotFoundException("No operator with that email.");
        }
        return users.getFirst();
    }
}
