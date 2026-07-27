package com.algotrading.backend.controller;

import com.algotrading.backend.dto.OiSignalResponse;
import com.algotrading.backend.service.OiSignalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/oi-signal")
@RequiredArgsConstructor
public class OiSignalController {

    private final OiSignalService oiSignalService;

    @GetMapping
    public OiSignalResponse getSignal() {
        return oiSignalService.getSignal();
    }
}
