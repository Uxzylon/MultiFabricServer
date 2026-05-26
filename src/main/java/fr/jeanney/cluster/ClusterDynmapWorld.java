package fr.jeanney.cluster;

public record ClusterDynmapWorld(
                String name,
                String title,
                int height,
                int minY,
                int seaLevel,
                boolean nether,
                boolean theEnd) {
}
