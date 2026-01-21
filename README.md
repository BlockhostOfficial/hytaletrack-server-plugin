# HytaleTrack Server Plugin

A Hytale server plugin that submits server data to [HytaleTrack](https://hytaletrack.com) for display on player and server profiles.

## Features

- **Real-time server status** - Report accurate player count and online status
- **Player sessions** - Track player join/leave events
- **Player statistics** - Submit playtime, achievements, and custom stats
- **Seamless integration** - Works with your existing HytaleTrack server profile

## Requirements

- A Hytale server running HytaleServer 1.0+
- A verified server on [HytaleTrack](https://hytaletrack.com)
- Java 17 or higher

## Installation

1. Download the latest release from the [Releases](https://github.com/BlockhostOfficial/hytaletrack-server-plugin/releases) page
2. Place the JAR file in your server's `plugins/` directory
3. Restart your server
4. Configure the plugin (see Configuration below)

## Configuration

1. Log in to [HytaleTrack](https://hytaletrack.com)
2. Go to **My Servers** and select your verified server
3. Click **Enable Plugin Integration** to generate an API key
4. Add the API key to your server's environment:

```bash
export HYTALETRACK_API_KEY="htk_your_api_key_here"
```

Or add it to your server startup script.

## Building from Source

```bash
# Clone the repository
git clone https://github.com/BlockhostOfficial/hytaletrack-server-plugin.git
cd hytale-server-plugin

# Build with Gradle
./gradlew build

# The JAR will be in build/libs/
```

## Development

### Project Structure

```
src/main/java/com/hytaletrack/serverplugin/
├── HytaleTrackServerPlugin.java    # Main plugin class
└── ...                              # Additional classes
```

### Running Tests

```bash
./gradlew test
```

## API Reference

This plugin communicates with the HytaleTrack API. For API documentation, see the [HytaleTrack Developer Docs](https://hytaletrack.com/docs).

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
- [Documentation](https://hytaletrack.com/docs) - Full documentation
- [Discord](https://discord.gg/hytaletrack) - Community Discord server
- [Issues](https://github.com/BlockhostOfficial/hytaletrack-server-plugin/issues) - Report bugs or request features
