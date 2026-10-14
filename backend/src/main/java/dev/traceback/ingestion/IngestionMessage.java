package dev.traceback.ingestion;

import java.util.UUID;

/** Fixed, versioned opaque references only; persisted outbox metadata is authoritative. */
public record IngestionMessage(UUID event, UUID job, UUID organization, UUID document, UUID revision) {
    public String encode() { return "v1:" + event + ":" + job + ":" + organization + ":" + document + ":" + revision; }
    public static IngestionMessage parse(String body) {
        if (body == null || body.length() != 187) throw new IllegalArgumentException("Invalid ingestion message");
        String[] parts = body.split(":", -1);
        if (parts.length != 6 || !parts[0].equals("v1")) throw new IllegalArgumentException("Invalid ingestion message");
        UUID[] ids = new UUID[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = UUID.fromString(parts[i + 1]);
            if (!ids[i].toString().equals(parts[i + 1])) throw new IllegalArgumentException("Invalid ingestion reference");
        }
        return new IngestionMessage(ids[0], ids[1], ids[2], ids[3], ids[4]);
    }
}
