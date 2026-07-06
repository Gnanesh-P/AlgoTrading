package com.algotrading.backend.service;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.TokenException;
import org.slf4j.Logger;

/**
 * Shared helpers for classifying and reacting to Kite Connect API errors.
 *
 * Two error classes matter operationally:
 *
 *  - Rate limiting (HTTP 429 "Too Many Requests"): Kite throttles the API (order placement
 *    especially). Without retry, a single 429 previously bubbled straight up as a generic
 *    "Order failed" RuntimeException and the leg was silently never placed. All order-placement
 *    call sites now retry a few times with exponential backoff before giving up.
 *
 *  - Session/token expiry (Kite's "TokenException" / "Invalid user session."): happens when a
 *    request_token is stale/reused during OAuth, or when the daily access token has expired.
 *    This is not retryable — the user must reconnect. We detect it and produce one clear,
 *    actionable message instead of leaking Kite's raw JSON error body to the UI.
 */
public final class KiteErrorUtil {

    private KiteErrorUtil() {
    }

    /** True if this error represents a Kite HTTP 429 (Too Many Requests) rate-limit response. */
    public static boolean isRateLimited(Throwable e) {
        if (e == null) return false;
        if (e instanceof KiteException ke && ke.code == 429) return true;
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("too many requests");
    }

    /** True if this error represents an expired/invalid Kite session (needs reconnect, not retry). */
    public static boolean isSessionExpired(Throwable e) {
        if (e == null) return false;
        if (e instanceof TokenException) return true;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("invalid user session")
                || lower.contains("tokenexception")
                || lower.contains("session expired")
                || lower.contains("incorrect `api_key`")
                || lower.contains("access_token");
    }

    /** Clear, actionable message shown to the user in place of Kite's raw error body. */
    public static String sessionExpiredMessage() {
        return "Kite session expired or invalid. Please reconnect Kite (Connect via OAuth, or paste a fresh "
                + "access token) and try again. If you just logged in via the Kite popup, that login link may "
                + "have already been used or expired — click Connect via OAuth again for a fresh one.";
    }

    /**
     * Call from a catch block after a Kite SDK call fails. If the failure was a 429 and retries
     * remain, sleeps for an exponential backoff period and returns true (caller should retry).
     * Otherwise returns false immediately (caller should give up / rethrow).
     *
     * @param attempt     1-based attempt number that just failed
     * @param maxAttempts total attempts allowed (e.g. 3 = up to 2 retries after the first try)
     */
    public static boolean shouldRetry(Throwable e, int attempt, int maxAttempts, String logPrefix, Logger log) {
        if (!isRateLimited(e) || attempt >= maxAttempts) return false;
        long delayMs = 400L * (1L << (attempt - 1)); // 400ms, 800ms, 1600ms, ...
        log.warn("{} rate-limited by Kite (HTTP 429) — retrying in {}ms (attempt {}/{})",
                logPrefix, delayMs, attempt, maxAttempts);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }
}
