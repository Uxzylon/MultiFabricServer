package fr.jeanney.cluster;

public record GatewayRoute(String backendHost, int backendPort, String nodeId) {
}
