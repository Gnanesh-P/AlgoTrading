package com.algotrading.backend.security;

import com.algotrading.backend.service.UserRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads user details from UserRegistryService (users.json).
 *
 * This replaces the old yaml-based AppCredentialsProperties approach which
 * always returned ROLE_TRADER for every user regardless of their actual role,
 * causing admin users to always get 403 on admin endpoints.
 *
 * UserRegistryService stores the real role (ADMIN / TRADER) per user,
 * and PlatformUserDetails returns the correct ROLE_ADMIN / ROLE_TRADER authority.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRegistryService userRegistry;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRegistry.findByUsername(username)
                .map(PlatformUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}
