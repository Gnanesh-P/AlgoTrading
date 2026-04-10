package com.algotrading.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PlatformUser {
    private String id;              // UUID, auto-generated on create
    private String username;
    private String passwordHash;    // BCrypt hashed — never store plaintext
    private String role;            // ADMIN | TRADER
    private boolean enabled;

    // Per-user plan limits
    private int    maxLotSize;      // max lots this user may trade per session
    private LocalDate planExpiryDate; // plan active until this date (inclusive)
    private String notes;           // admin notes e.g. "Paid ₹2000 for 3 months"

    // Per-user Zerodha credentials (each customer links their own Kite account)
    private String kiteApiKey;
    private String kiteApiSecret;
    private String kiteAccessToken; // refreshed daily after Kite OAuth login
    private String kiteRedirectUrl; // e.g. https://app.example.com/api/kite/callback

    // Audit
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** True when plan has not expired */
    @JsonIgnore
    public boolean isPlanActive() {
        return planExpiryDate != null && !LocalDate.now().isAfter(planExpiryDate);
    }
}
