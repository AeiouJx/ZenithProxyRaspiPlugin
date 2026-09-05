# ZenithProxy Raspi Plugin

A ZenithProxy plugin designed for headless setups (e.g. a Raspberry Pi) running bots on the `3c3u.org` server.

Built on top of [ZenithProxy](https://github.com/rfresh2/ZenithProxy), a Minecraft proxy and bot.

## Features

### AutoLogin (`autoLogin` / `autologin`)
For offline-authenticated accounts on `3c3u.org`:

- **Auto /login** — when the server prompts for login, automatically sends `/login <password>` using the password from your ZenithProxy config.
- **Auto /captcha** — detects captcha prompts (e.g. `你必须输入验证码才能登入: /captcha lje0a`), extracts the code with a case-insensitive regex, and automatically sends `/captcha <code>`.

Only activates when `accountType` is `OFFLINE` and the server address is `3c3u.org`.

> Reconnection on disconnect is handled by the built-in `AutoReconnect` module of ZenithProxy — this plugin does not re-implement it.

### Promoter (`promoter` / `promo`)
Automatically sends configured messages or whispers in-game to promote your server:

- Custom message list via `add` / `addAt` / `addAll`, bulk-add delimited by `,,`
- `whisper` mode — sends private messages to players from the tab list, one per player
- `whilePlayerConnected` — only spam when no player is connected
- `randomOrder` / `appendRandom` — shuffle messages and append random suffixes

> **Warning:** spamming can get your account muted. Use at your own risk.

## Installation

Plugins are only supported on the `java` ZenithProxy release channel.

1. Download the plugin jar from the [Releases](https://github.com/AeiouJx/ZenithProxyRaspiPlugin/releases) page.
2. Place the jar in the `plugins` folder, next to the ZenithProxy launcher.
3. Restart ZenithProxy. Plugins are loaded at startup; hot-reloading is not supported.

## Usage

| Command | Description |
|---|---|
| `autoLogin on/off` | Toggle the AutoLogin module |
| `promoter on/off` | Toggle the Promoter module |
| `promoter whisper on/off` | Toggle whisper mode |
| `promoter whilePlayerConnected on/off` | Toggle pausing while a player is connected |
| `promoter delayTicks <int>` | Set interval between messages (in ticks) |
| `promoter add <message>` | Add a message |
| `promoter addAll <msg1>,,<msg2>` | Bulk-add messages |
| `promoter del <index>` | Remove a message by index |
| `promoter list` | List all messages |

## Building

Requires Java 25 (target release 21).

```bash
./gradlew build
```

The built plugin jar is in `build/libs`.

## Configuration

Configuration is stored in the `plugins` directory as a JSON file named after the plugin id (`raspi-plugin`).

- `autoLogin.enabled` — enable the AutoLogin module
- `promoter.*` — Promoter settings (message list, delays, mode toggles)