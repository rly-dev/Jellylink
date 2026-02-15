<!--
Title: Jellylink — Self-Hosted Jellyfin Lavalink Plugin for Discord Music Bots
Description: Jellylink is an open-source Lavalink plugin that integrates Jellyfin with Lavalink so Discord music bots can stream audio directly from your Jellyfin media server. Self-hosted, private, and high-quality audio streaming for your bot.
Keywords: Jellyfin Lavalink plugin, Jellyfin music bot, Discord music bot self-hosted, stream music from Jellyfin to Discord, Lavalink plugin, Jellyfin audio streaming
-->
# Jellylink – Jellyfin Music Plugin for Lavalink

Play music from your **Jellyfin** media server through **Lavalink**. Jellylink is a Lavalink plugin that lets Discord bots search and stream audio directly from a Jellyfin library — no YouTube needed.

> **Keywords:** Jellyfin Lavalink plugin, Jellyfin Discord music bot, Lavalink Jellyfin source, stream Jellyfin audio Discord, self-hosted music bot

---

## Features

- **Search your Jellyfin library** from any Lavalink client using the `jfsearch:` prefix
- **Stream audio directly** — plays FLAC, MP3, OGG, and other formats from Jellyfin
- **Cover art & metadata** — track title, artist, album, duration, and artwork are passed to your client
- **Configurable audio quality** — stream original files or transcode to a specific bitrate/codec
- **Username/password authentication** — no need to manage API keys manually
- Works alongside YouTube, SoundCloud, Spotify, and all other Lavalink sources

---

## Installation

### Prerequisites

- [Lavalink v4](https://github.com/lavalink-devs/Lavalink) (tested with 4.0.8)
- A running [Jellyfin](https://jellyfin.org/) server with music in its library
- Java 17+

### Step 1 — Add the Plugin to Lavalink

Add the following to your Lavalink `application.yml`:

```yaml
lavalink:
  plugins:
    - dependency: com.github.Myxelium:Jellylink:v0.2.0
      repository: https://jitpack.io
```

> **Tip:** Replace `v0.1.0` with the version you want. Check available versions on the [Releases](https://github.com/Myxelium/Jellylink/releases) page.

Lavalink will automatically download the plugin on startup.

<details>
<summary><strong>Alternative: Manual Installation</strong></summary>

**Option A: Download the JAR**

Grab the latest `jellylink-x.x.x.jar` from the [Releases](https://github.com/Myxelium/Jellylink/releases) page.

**Option B: Build from Source**

```bash
git clone https://github.com/Myxelium/Jellylink.git
cd Jellylink
./gradlew build
```

The JAR will be at `build/libs/jellylink-0.1.0.jar`.

Copy the JAR into your Lavalink `plugins/` directory:

```
lavalink/
├── application.yml
├── Lavalink.jar
└── plugins/
    └── jellylink-0.1.0.jar    ← put it here
```

If you use **Docker**, mount it into the container's plugins volume:

```yaml
volumes:
  - ./application.yml:/opt/Lavalink/application.yml
  - ./plugins/:/opt/Lavalink/plugins/
```

</details>

### Step 2 — Configure Lavalink

Add the following to your `application.yml` under `plugins:`:

```yaml
plugins:
  jellylink:
    jellyfin:
      baseUrl: "http://your-jellyfin-server:8096"
      username: "your_username"
      password: "your_password"
      searchLimit: 5          # max results to return (default: 5)
      audioQuality: "ORIGINAL" # ORIGINAL | HIGH | MEDIUM | LOW | custom kbps
      audioCodec: "mp3"       # only used when audioQuality is not ORIGINAL
      tokenRefreshMinutes: 30 # re-authenticate every N minutes (0 = only on 401)
```

#### Audio Quality Options

| Value       | Bitrate   | Description                              |
|-------------|-----------|------------------------------------------|
| `ORIGINAL`  | —         | Serves the raw file (FLAC, MP3, etc.)    |
| `HIGH`      | 320 kbps  | Transcoded via Jellyfin                  |
| `MEDIUM`    | 192 kbps  | Transcoded via Jellyfin                  |
| `LOW`       | 128 kbps  | Transcoded via Jellyfin                  |
| `256`       | 256 kbps  | Any number = custom bitrate in kbps      |

#### Docker Networking

If Lavalink runs in Docker and Jellyfin runs on the host:
- Use your host's LAN IP (e.g. `http://192.168.1.100:8096`)
- Or use `http://host.docker.internal:8096` (Docker Desktop)
- Or use `http://172.17.0.1:8096` (Docker bridge gateway)

### Step 3 — Restart Lavalink

Restart Lavalink and check the logs. You should see:

```
Loaded plugin: jellylink-jellyfin
```

Verify at `GET /v4/info` — `jellyfin` should appear under `sourceManagers`.

---

## Usage

Search your Jellyfin library using the `jfsearch:` prefix when loading tracks:

```
jfsearch:Bohemian Rhapsody
jfsearch:Daft Punk
jfsearch:Bach Cello Suite
```

### Example with Lavalink4NET (C#)

> **Lavalink4NET users:** Install the companion NuGet package [Lavalink4NET.Jellyfin](https://github.com/Myxelium/Lavalink4NET.Jellyfin) for built-in search mode support, query parsing, and source detection.

```bash
dotnet add package Lavalink4NET.Jellyfin
```

```csharp
using Lavalink4NET.Jellyfin;
using Lavalink4NET.Rest.Entities.Tracks;

// Parse user input — automatically detects jfsearch:, ytsearch:, scsearch:, etc.
var (searchMode, cleanQuery) = SearchQueryParser.Parse(
    "jfsearch:Bohemian Rhapsody",
    defaultMode: JellyfinSearchMode.Jellyfin  // default when no prefix
);

var options = new TrackLoadOptions { SearchMode = searchMode };
var result = await audioService.Tracks.LoadTracksAsync(cleanQuery, options);
```

Or use `ParseExtended` for detailed source info:

```csharp
var result = SearchQueryParser.ParseExtended(userInput, JellyfinSearchMode.Jellyfin);

if (result.IsJellyfin)
    Console.WriteLine("Searching Jellyfin library...");

Console.WriteLine($"Source: {result.SourceName}, Query: {result.Query}");
```

### Example with Lavalink.py (Python)

```python
results = await player.node.get_tracks("jfsearch:Bohemian Rhapsody")
```

### Example with Shoukaku (JavaScript)

```javascript
const result = await node.rest.resolve("jfsearch:Bohemian Rhapsody");
```

The plugin only handles identifiers starting with `jfsearch:`. All other sources (YouTube, SoundCloud, Spotify, etc.) continue to work normally.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Jellyfin authentication failed` | Check `baseUrl`, `username`, and `password`. Make sure the URL is reachable from the Lavalink host/container. |
| `No Jellyfin results found` | Verify the song exists in your Jellyfin library and that the user has access to it. |
| `Unknown file format` | Update to the latest version — this was fixed by using direct audio streaming. |
| `No cover art` | Update to the latest version — artwork URLs are now always included. Jellyfin has to be public to internet.|

---

## License

MIT
