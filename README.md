# MultiFabricServer

Experimental Fabric mod branch for integrated multi-server orchestration in one JVM.

## Current status

This branch is a clean architecture reset.

- The old multi-dimension implementation was removed.
- A new cluster runtime was added.
- Node lifecycle exists (register, start request, stop request, status).
- Embedded MinecraftServer startup is not implemented yet.

## Runtime model

- One host Fabric server loads this mod.
- Cluster nodes are defined in a JSON config.
- Each node has its own runtime directory under the active save.
- Starting a node currently moves it to FAILED with an explicit reason because embedded server instancing is still pending.

## Config file

On first server start, this file is generated automatically:

- multifabricserver-cluster.json in the active world root

Default content:

{
	"enabled": false,
	"nodes": [
		{
			"id": "creative",
			"worldName": "creativeworld",
			"listenPort": 25580,
			"autoStart": false
		}
	]
}

## Commands

- /cluster status
- /cluster start <node>
- /cluster stop <node>

Permission requirement uses command-level GAMEMASTERS.

## Development notes

- Build check used for this reset: ./gradlew compileJava
- This branch is intended as a proof-of-concept base to iterate toward true embedded server instances.
