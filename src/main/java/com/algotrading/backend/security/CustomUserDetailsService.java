package com.algotrading.backend.security;

import com.algotrading.backend.config.AppCredentialsProperties;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads user details from the {@code app.credentials.users[]} list in application.yml.
 * Supports up to 4 users sharing a single Zerodha account:
 *   APP_USERNAME / APP_PASSWORD  — user 1 (original admin)
 *   APP_USER2    / APP_PASS2     — user 2
 *   APP_USER3    / APP_PASS3     — user 3
 *   APP_USER4    / APP_PASS4     — user 4
 *
 * Entries with a blank username are silently skipped.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AppCredentialsProperties credentials;
    private final PasswordEncoder passwordEncoder;

    public CustomUserDetailsService(AppCredentialsProperties credentials,
                                    PasswordEncoder passwordEncoder) {
        this.credentials    = credentials;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String requestedUsername) throws UsernameNotFoundException {
        return credentials.getUsers().stream()
                .filter(AppCredentialsProperties.UserEntry::isValid)
                .filter(u -> u.getUsername().equals(requestedUsername))
                .findFirst()
                .map(u -> new User(
                        u.getUsername(),
                        passwordEncoder.encode(u.getPassword()),
                        List.of(new SimpleGrantedAuthority("ROLE_TRADER"))))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + requestedUsername));
    }
}
