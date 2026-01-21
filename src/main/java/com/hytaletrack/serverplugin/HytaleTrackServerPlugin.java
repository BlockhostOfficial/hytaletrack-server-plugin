package com.hytaletrack.serverplugin;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;

/**
 * HytaleTrack Server Plugin
 *
 * Submits server data to HytaleTrack for display on player and server profiles.
 *
 * @see <a href="https://hytaletrack.com">HytaleTrack</a>
 * @see <a href="https://github.com/BlockhostOfficial/hytaletrack-server-plugin">GitHub</a>
 */
public class HytaleTrackServerPlugin extends JavaPlugin {

    public HytaleTrackServerPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        System.out.println("[HytaleTrack] Server Plugin loaded!");
        System.out.println("[HytaleTrack] Visit https://hytaletrack.com to configure your server.");
    }
}
