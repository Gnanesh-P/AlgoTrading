package com.algotrading.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Value("${app.credentials.username}")
    private String username;

    @Value("${app.credentials.password}")
    private String password;

    private final PasswordEncoder passwordEncoder;

    public CustomUserDetailsService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserDetails loadUserByUsername(String requestedUsername) throws UsernameNotFoundException {
        if (!username.equals(requestedUsername)) {
            throw new UsernameNotFoundException("User not found: " + requestedUsername);
        }
        return new User(username, passwordEncoder.encode(password),
                List.of(new SimpleGrantedAuthority("ROLE_TRADER")));
    }
}
