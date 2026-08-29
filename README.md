# MultiFabricServer

Experimental Fabric server-side mod for running multiple lazy-loaded Minecraft server instances, called clusters, inside one JVM.

Players use `/cluster travel <target>` or `/cluster tp <target>` to move between the host and cluster nodes. Nodes start on demand and stop after they have been empty for the configured idle delay. Player list, player count, chat, join/leave messages, advancement messages, and command feedback are shared across running clusters.

## Velocity And Standalone Modes

The mod can run without Velocity. In standalone mode, use Minecraft transfer packets and, for a public server, the built-in gateway:

```json
{
  "enabled": true,
  "gatewayEnabled": true,
  "transferHost": "play.example.com",
  "gatewayBindHost": "0.0.0.0",
  "gatewayBindPort": 25565,
  "nodeIdleStopSeconds": 300,
  "nodes": [
    {
      "id": "creative",
      "worldName": "creativeworld",
      "enabled": true
    }
  ]
}
```

With this setup, the gateway listens on `gatewayBindPort` and forwards players to the host or their target lazy cluster. The host Minecraft server must listen on a different port, for example `25566`, because the gateway owns the public port.

Velocity can still be used for extended behavior such as preserving chat UI/history during switches. Install the companion Velocity plugin built by this repo and Velocity only needs the host server in `velocity.toml`.

When `seamlessProxySwitchEnabled` is enabled, the Fabric mod sends the companion plugin a dynamic backend registration immediately before it sends Velocity's normal `Connect` plugin message. That lets newly-created clusters work without manually adding each cluster to `velocity.toml`.

Without the companion Velocity plugin, `seamlessProxySwitchEnabled` still requires each target cluster name to be registered in Velocity. If Velocity only has the host server configured and you do not want the companion plugin, disable `seamlessProxySwitchEnabled` and use the transfer/gateway path instead.

## Config File

On first server start, this file is generated in the active world root:

```text
multifabricserver-cluster.json
```

Default content is disabled and safe to edit:

```json
{
  "enabled": false,
  "gatewayEnabled": false,
  "seamlessProxySwitchEnabled": false,
  "proxyHostServerName": "host",
  "transferHost": "127.0.0.1",
  "gatewayBindHost": "0.0.0.0",
  "gatewayBindPort": 25565,
  "nodeIdleStopSeconds": 300,
  "nodes": [
    {
      "id": "creative",
      "worldName": "creativeworld",
      "enabled": true
    }
  ]
}
```

`proxyServerName` is optional per node. If omitted, seamless Velocity mode falls back to the node id as the Velocity server name.

## Velocity Companion Plugin

Build both the Fabric mod and Velocity companion plugin:

```bash
./gradlew clean build
```

Install:

```text
build/libs/multifabricserver-mc26.1-1.0.0.jar -> Fabric server mods/
velocity/build/libs/multifabricserver-velocity-1.0.0.jar -> Velocity plugins/
```

Then keep only the host backend in `velocity.toml`, for example:

```toml
[servers]
host = "127.0.0.1:25566"
try = ["host"]
```

Cluster nodes can omit `proxyServerName`; the node id becomes the dynamic Velocity server name.

## Commands

- `/cluster status`
- `/cluster players`
- `/cluster reload`
- `/cluster enable`
- `/cluster enable <node>`
- `/cluster disable`
- `/cluster disable <node>`
- `/cluster start <node>`
- `/cluster stop <node>`
- `/cluster remove <node>`
- `/cluster travel <host|node>`
- `/cluster tp <host|node>`

Admin commands require command-level `GAMEMASTERS`.

## Development

Build:

```bash
./gradlew clean build
```
