# HytaleTrack Server Plugin

A Hytale server plugin that submits server data to [HytaleTrack](https://hytaletrack.com) for display on player and server profiles.

## Features

- **Real-time server status** - Automatically reports player count every 30 seconds
- **Player session tracking** - Tracks when players join your server
- **Seamless integration** - Works with your existing HytaleTrack server profile
- **Async submissions** - Non-blocking HTTP requests that won't lag your server

## Requirements

- A Hytale server running HytaleServer 1.0+
- A **verified** server on [HytaleTrack](https://hytaletrack.com)
- Java 21 or higher (for virtual threads)

## Installation

1. Download the latest release from the [Releases](https://github.com/BlockhostOfficial/hytaletrack-server-plugin/releases) page
2. Place the JAR file in your server's `plugins/` directory
3. Configure the plugin with your API key (see Configuration below)
4. Restart your server

## Configuration

### Step 1: Get your API Key

1. Log in to [HytaleTrack](https://hytaletrack.com)
2. Go to **Account → My Servers**
3. Select your **verified** server
4. Click **Enable Plugin Integration**
5. **Copy the API key immediately** - it will only be shown once!

### Step 2: Set the Environment Variable

Add the API key to your server's environment:

```bash
export HYTALETRACK_API_KEY="htk_your_api_key_here"
```

Or add it to your server startup script before launching the server.

### Optional: Custom API URL

If you're running a self-hosted HytaleTrack instance:

```bash
export HYTALETRACK_API_URL="https://your-instance.com/api/server-plugin/submit"
```

## What Data is Submitted

| Data Type | When | What |
|-----------|------|------|
| **Status** | Every 30 seconds | Player count, online status |
| **Player Join** | When a player joins | Player UUID and username |
| **Player Leave** | When tracked (see below) | Player UUID and username |

### Note on Player Leave Events

Due to HytaleServer API limitations, player disconnect events may require manual integration. You can call the plugin's public method from your own code:

```java
HytaleTrackServerPlugin plugin = /* get plugin instance */;
plugin.onPlayerLeave(playerUuid);
// or
plugin.onPlayerLeave(playerName);
```

## Building from Source

```bash
# Clone the repository
git clone https://github.com/BlockhostOfficial/hytaletrack-server-plugin.git
cd hytaletrack-server-plugin

# Build with Gradle
./gradlew build

# The JAR will be in build/libs/
```

## Troubleshooting

### "HYTALETRACK_API_KEY environment variable is not set!"

Make sure you've exported the environment variable before starting the server:

```bash
export HYTALETRACK_API_KEY="htk_..."
java -jar server.jar
```

### "API key doesn't have expected format"

API keys should start with `htk_`. Make sure you copied the full key from HytaleTrack.

### "Invalid or disabled API key"

- Check that your server is verified on HytaleTrack
- Check that plugin integration is enabled in My Servers
- Try regenerating the API key

### No data appearing on HytaleTrack

- Check the server console for `[HytaleTrack]` messages
- Verify the API key is correct
- Make sure your server can reach `hytaletrack.com`

## API Reference

This plugin communicates with the HytaleTrack API:

```
POST https://hytaletrack.com/api/server-plugin/submit
Header: X-Api-Key: htk_...
Content-Type: application/json

// Status update
{"type": "status", "data": {"playerCount": 5, "isOnline": true}}

// Player join
{"type": "player_join", "data": {"playerUuid": "uuid", "playerName": "name"}}

// Player leave
{"type": "player_leave", "data": {"playerUuid": "uuid", "playerName": "name"}}
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Links

- [HytaleTrack](https://hytaletrack.com) - The main HytaleTrack website
- [My Servers](https://hytaletrack.com/account/servers) - Manage your servers and get API keys
- [Issues](https://github.com/BlockhostOfficial/hytaletrack-server-plugin/issues) - Report bugs or request features
