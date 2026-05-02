package com.algotrading.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebMvcConfig {

    @GetMapping("/login")
    public void loginPage(HttpServletRequest req, HttpServletResponse res) throws Exception {
        req.getRequestDispatcher("/login.html").forward(req, res);
    }

    @GetMapping("/algo")
    public void algoPage(HttpServletRequest req, HttpServletResponse res) throws Exception {
        req.getRequestDispatcher("/index.html").forward(req, res);
    }
}
