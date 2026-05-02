package com.algotrading.backend.controller;

import com.algotrading.backend.dto.LoginRequest;
import com.algotrading.backend.dto.LoginResponse;
import com.algotrading.backend.security.JwtTokenProvider;
import com.algotrading.backend.service.UserRegistryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRegistryService userRegistryService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        String token = tokenProvider.generateToken(authentication);
        var user = userRegistryService.findByUsername(request.getUsername());
        String role       = user.map(u -> u.getRole())      .orElse("TRADER");
        int    maxLotSize = user.map(u -> u.getMaxLotSize()).orElse(1);
        log.info("User logged in: {} (role={}, maxLots={})", request.getUsername(), role, maxLotSize);
        return ResponseEntity.ok(new LoginResponse(token, request.getUsername(), role, maxLotSize));
    }
}
