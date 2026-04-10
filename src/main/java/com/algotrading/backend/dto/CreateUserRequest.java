package com.algotrading.backend.dto;

import lombok.Data;
import java.time.LocalDate;

@Data
public class CreateUserRequest {
    private String    username;
    private String    password;        // plain-text (hashed server-side)
    private String    role;            // ADMIN | TRADER (default: TRADER)
    private int       maxLotSize;      // max lots allowed (default: 1)
    private LocalDate planExpiryDate;  // yyyy-MM-dd
    private String    notes;
    private String    kiteApiKey;
    private String    kiteApiSecret;
    private String    kiteRedirectUrl;
}
