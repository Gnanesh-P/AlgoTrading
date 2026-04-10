package com.algotrading.backend.dto;

import lombok.Data;
import java.time.LocalDate;

@Data
public class UpdateUserRequest {
    private String    newPassword;      // optional — leave null to keep current
    private String    role;
    private int       maxLotSize;
    private LocalDate planExpiryDate;
    private boolean   enabled;
    private String    notes;
    private String    kiteApiKey;
    private String    kiteApiSecret;
    private String    kiteAccessToken;
    private String    kiteRedirectUrl;
}
