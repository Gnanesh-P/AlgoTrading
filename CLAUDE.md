# AlgoTradingBackend

NIFTY/Bank Nifty options intraday algo-trading backend with Zerodha Kite Connect integration and a dark-theme web UI.

## Stack

- Spring Boot 3.2.4, Java 17
- Zerodha Kite Connect Java SDK 4.0.0 (jitpack.io)
- Spring Security (JWT via JJWT 0.12.5), stateless
- Spring WebSocket (STOMP) for live push updates
- OkHttp 4.12.0, Jackson (+ jsr310 for LocalDateTime)
- Lombok
- No DB, no Redis — in-memory `ConcurrentHashMap` caches + file-based session persistence
- Frontend: vanilla HTML/CSS/JS in `src/main/resources/static/` (no build step)
- Final jar name: `algo-trading-proxy` (see `pom.xml`), default port from `application.properties`

## Build & Run

```
mvn clean package
java -jar target/algo-trading-proxy-1.0.0.jar
```

Run tests: `mvn test` (as of 2026-07, `src/test/java` has no test files — no test coverage yet).

## Directory Map

`src/main/java/com/algotrading/backend/`

- `AlgoTradingApplication.java` — Spring Boot entrypoint
- `broker/` — `BrokerService` interface, `BrokerServiceFactory`, `LiveBrokerService` (real Kite orders), `PaperBrokerService` (simulated)
- `cache/MarketDataCache.java` — global tick store (`latestTicks`, `tickHistory` keyed by instrument symbol) + **legacy** singleton `currentConfig`/`currentSession` fields (see Architecture Notes below)
- `config/` — `AppConfig`, `AppCredentialsProperties`, `KiteProperties`, `ProxyProperties`, `SecurityConfig` (permits `/`, `/api/auth/**`, `/ws/**`, `/api/kite/callback`, static assets), `WebMvcConfig`, `WebSocketConfig` (STOMP `/ws` endpoint)
- `controller/` — `AdminController`, `AlgoController` (**primary multi-user API**: `/algo/*`), `AuthController` (`/api/auth/login`), `ConfigController` (`/api/config`), `GlobalExceptionHandler`, `InstrumentController`, `KiteController` (`/api/kite/*` — login-url, callback, access-token, instruments), `MarketDataController`, `MarketQuotesController`, `TradingController` (**legacy** singleton `/api/trading/*`)
- `dto/` — request/response records: `AlgoStartRequest`, `AlgoStatusResponse`, `AlgoUpdateParamsRequest`, `CreateUserRequest`, `LoginRequest`, `LoginResponse`, `MarketTickRequest`, `OptionChainResponse`, `TradeSessionResponse`, `TradingConfigRequest`, `UpdateUserRequest`
- `engine/` — `TradingEngineRegistry` (creates/holds one `UserTradingEngine` per logged-in user, keyed by username; routes ticks to all active engines; cron square-off at 15:29 IST), `UserTradingEngine` (~1120 lines — the core strategy state machine, see below)
- `model/` — `Candle`, `ExpiryType` (CURRENT_WEEK/NEXT_WEEK), `KiteInstrument`, `MarketTick`, `OptionType` (CE/PE), `PlatformUser`, `StrategyState` (IDLE/WAITING_FOR_CANDLES/IN_POSITION/STOPPED), `StrikeMode` (MANUAL/AUTO_ATM), `TradeEntry` (one leg), `TradeMode` (LIVE/PAPER), `TradeSession` (one day's session), `TradingConfig` (strategy parameters)
- `security/` — `CustomUserDetailsService`, `JwtAuthenticationFilter`, `JwtTokenProvider`, `PlatformUserDetails`
- `service/` — `AlgoSchedulerService` (auto start/stop via env-configured scheduler), `CandleAggregatorService` (legacy global candle builder), `ConfigService`, `KiteAuthService`, `KiteInstrumentService` (fetches/parses NFO instrument CSV, resolves option symbols), `KiteRestPollService`, `KiteTickerService` (1s LTP polling, `@Lazy`), `KiteTokenStore`, `MarketDataService` (tick ingestion entrypoint — updates cache, feeds legacy `TradingStrategyService`, routes to `TradingEngineRegistry`), `MarketQuotesService`, `OptionInstrumentService` (ATM/strike computation, weekly expiry resolution incl. NSE holiday table), `SessionPersistenceService` (crash-recovery persistence), `TelegramService`, `TradingStrategyService` (**legacy** singleton strategy logic), `UserRegistryService`
- `websocket/TradeWebSocketController.java`

`src/main/resources/`
- `application.properties`
- `static/index.html`, `static/css/style.css`, `static/js/app.js`

## Current Strategy Logic (Nifty 1-Min Scalping — the only strategy today)

Implemented in `engine/UserTradingEngine.java`, driven by `TradingConfig` on `TradeSession`.

1. Session starts at a configured time; waits for 1st and 2nd 1-minute futures candles (`session.firstCandle`, `secondCandle`).
2. Entry decision (buy-only today): `close1 > close2 → enter PE`, else `enter CE`.
3. Reversal on every subsequent candle close while `IN_POSITION`: in CE, `currentClose < close1 → reverse to PE`; in PE, `currentClose > close1 → reverse to CE`. Capped by `config.maxReversals`.
4. Strike/instrument locked once per day (`lockedCeInstrument/Strike`, `lockedPeInstrument/Strike`, `lockedExpiry`) — reused across reversals.
5. Exit on target profit, stop loss, or reversal cap exhaustion; P&L tracked cumulatively per leg in `TradeEntry.pnl` and summed in `TradeSession.cumulativePnL`/`openPnL`.
6. `futuresInstrument` is configurable per `TradingConfig` (not hardcoded to NIFTY), but strike math in `OptionInstrumentService` assumes a 50-point strike gap (NIFTY-style) and lot size 65 is hardcoded via `getTotalQuantity()`.

## Architecture Notes (important — read before extending)

- **Two parallel code paths exist today:** the legacy singleton path (`TradingStrategyService` + `MarketDataCache.currentConfig/currentSession` + `TradingController` `/api/trading/*`), and the newer per-user path (`TradingEngineRegistry` + `UserTradingEngine` + `AlgoController` `/algo/*`). New work should build on the per-user (`AlgoController`/`UserTradingEngine`) path; the legacy singleton path is being phased out.
- `MarketDataCache` is a **global, unkeyed-by-user** tick store (`Map<String, MarketTick> latestTicks` keyed only by instrument symbol) — fine for shared LTP data, but its legacy `currentConfig`/`currentSession` fields don't fit a multi-strategy-per-user model and should not be extended further.
- `UserTradingEngine` is currently **one engine per user**, supporting **one strategy/one instrument at a time** (NIFTY futures + its CE/PE). It is not yet designed for multiple concurrent strategies (e.g., NIFTY scalping + Bank Nifty scalping + a VWAP breakout strategy) running simultaneously for the same user — that requires a registry keyed by `(username, strategyId)` rather than just `username`, plus a shared/generalized candle+tick engine that can host different strategy implementations.
- WebSocket broadcasts go to two STOMP topics: `/topic/trade-updates/{username}` (per-user) and `/topic/trade-updates` (global) — see `UserTradingEngine.broadcastUpdate()`.
- `OptionInstrumentService` already supports `ExpiryType.CURRENT_WEEK`/`NEXT_WEEK` and auto-rolls to next week if ≤1 trading day to expiry — this logic is reusable for new strategies.
- `KiteInstrumentService.findNiftyOption(expiry, strike, type)` resolves real tradable symbols from Kite's instrument cache rather than string-formatting them — reuse this pattern for Bank Nifty (`findBankNiftyOption` or a generalized `findOption(indexName, ...)`).

## Credentials & Config

- Login: `admin` / `admin123` (see `application.properties` / `AppCredentialsProperties`)
- Kite: set `KITE_API_KEY`, `KITE_API_SECRET` env vars, then connect via UI "Connect Kite" or `POST /api/kite/access-token`
- Telegram: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` env vars
- Env vars: `PORT`, `JWT_SECRET`, `JWT_EXPIRY_MS`, `APP_USERNAME`/`PASSWORD` (+ `APP_USER2-4`/`PASS2-4`), `USERS_FILE`, `SESSION_FILE`, `KITE_ACCESS_TOKEN`, `KITE_REDIRECT_URL`, `SCHEDULER_ENABLED`, `SCHEDULER_USERNAME`, `SCHEDULER_LOTS`, `SCHEDULER_SL`, `SCHEDULER_TARGET`, `SCHEDULER_MAX_REVERSALS`, `SCHEDULER_TRAILING`, `SCHEDULER_SQUARE_OFF_EOD`, `SCHEDULER_PAPER_TRADE`

## UI Notes

- Role-based visibility: `localStorage.userRole` (`ADMIN` vs `TRADER`) toggles `.admin-only`/`.user-only` elements via `applyRoleVisibility()` in `app.js`.
- Admin sees: candle section, trade logs table, max-reversals config, Started By/Reversals/Entry Direction/Locked CE-PE rows, start time 09:15–15:27.
- Trader sees: signal monitor + trade summary cards only, start time 09:15–09:20, max reversals fixed at 10 (hidden field).
- Theme toggle (☀️/🌙) persisted in `localStorage`.
- Currently single-strategy UI only (one config panel, one start/stop). A multi-strategy UI (independent enable/disable, config, and status per strategy) does not exist yet.
