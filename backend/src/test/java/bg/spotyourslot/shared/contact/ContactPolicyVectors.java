package bg.spotyourslot.shared.contact;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the single authoritative golden-vector file shared with the frontend tests
 * ({@code shared-test-data/contact-policy-vectors.json}).
 */
final class ContactPolicyVectors {
    private static final String RELATIVE_PATH = "shared-test-data/contact-policy-vectors.json";

    private ContactPolicyVectors() {
    }

    record Accepted(String id, String input, String canonical) {
        @Override
        public String toString() {
            return id;
        }
    }

    record Rejected(String id, String input) {
        @Override
        public String toString() {
            return id;
        }
    }

    static List<Accepted> accepted(String kind) {
        List<Accepted> vectors = new ArrayList<>();
        for (JsonNode node : load().get(kind).get("accepted")) {
            vectors.add(new Accepted(
                    node.get("id").asString(),
                    node.get("input").asString(),
                    node.get("canonical").asString()));
        }
        return vectors;
    }

    static List<Rejected> rejected(String kind) {
        List<Rejected> vectors = new ArrayList<>();
        for (JsonNode node : load().get(kind).get("rejected")) {
            vectors.add(new Rejected(node.get("id").asString(), node.get("input").asString()));
        }
        return vectors;
    }

    static List<String> blank(String kind) {
        List<String> inputs = new ArrayList<>();
        for (JsonNode node : load().get(kind).get("blank")) {
            inputs.add(node.asString());
        }
        return inputs;
    }

    private static JsonNode load() {
        Path file = locate();
        try {
            return JsonMapper.builder().build().readTree(Files.readString(file));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read the shared contact vectors", exception);
        }
    }

    // Maven runs from backend/; an IDE may run from the repository root.
    private static Path locate() {
        for (Path candidate : List.of(Path.of("..", RELATIVE_PATH), Path.of(RELATIVE_PATH))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("The shared contact vector file was not found");
    }
}
