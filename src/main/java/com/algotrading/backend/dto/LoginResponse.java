package com.algotrading.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String tokenType = "Bearer";
    private String username;
    private String role;
    private int    maxLotSize;

    public LoginResponse(String token, String username) {
        this.token = token;
        this.username = username;
    }

    public LoginResponse(String token, String username, String role, int maxLotSize) {
        this.token      = token;
        this.username   = username;
        this.role       = role != null ? role : "TRADER";
        this.maxLotSize = maxLotSize;
    }
}
