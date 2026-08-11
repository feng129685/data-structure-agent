package com.feng.dsagent.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feng.dsagent.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DsvpAnimationAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DsvpAnimationAdapter adapter = new DsvpAnimationAdapter(objectMapper, new AnimationValidator());

    @Test
    void adaptsEveryPublishedDsvpExampleToTheExistingRendererContract() throws Exception {
        Path examples = contractPath("examples/dsvp");
        List<Path> files;
        try (var stream = Files.list(examples)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).hasSize(9);

        for (Path file : files) {
            DsvpSimulationResponse result = adapter.adapt(objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8)));
            assertThat(result.protocol()).as(file.toString()).isEqualTo("dsvp/1.0");
            assertThat(result.trace().path("trace_id").asText()).startsWith("dsvp_");
            assertThat(new AnimationValidator().validate(result.animationData()).valid()).as(file.toString()).isTrue();
        }
    }

    @Test
    void rejectsUnknownOperationsAndOversizedPayloadsWithStableErrors() throws Exception {
        JsonNode unknown = objectMapper.readTree("""
            {"version":"1.0","structure":"stack","operation":"execute","params":{},"initial_state":{"data":[]}}
            """);
        assertThatThrownBy(() -> adapter.adapt(unknown))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("DSVP_OPERATION_UNSUPPORTED");

        JsonNode tooLarge = objectMapper.readTree("""
            {"version":"1.0","structure":"stack","operation":"peek","params":{},"initial_state":{"data":%s}}
            """.formatted(java.util.stream.IntStream.range(0, 65).boxed().toList()));
        assertThatThrownBy(() -> adapter.adapt(tooLarge))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("DSVP_INITIAL_STATE_TOO_LARGE");
    }

    @Test
    void rejectsSourceReferencesThatCannotFitThePersistenceContract() throws Exception {
        JsonNode nonTextual = objectMapper.readTree("""
            {"version":"1.0","structure":"stack","operation":"peek","params":{},"initial_state":{"data":[]},"source_ref":42}
            """);
        assertThatThrownBy(() -> adapter.adapt(nonTextual))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("DSVP_REQUEST_INVALID");

        JsonNode tooLong = objectMapper.readTree("""
            {"version":"1.0","structure":"stack","operation":"peek","params":{},"initial_state":{"data":[]},"source_ref":"%s"}
            """.formatted("x".repeat(161)));
        assertThatThrownBy(() -> adapter.adapt(tooLong))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("DSVP_REQUEST_INVALID");
    }

    private Path contractPath(String relative) {
        return Path.of(System.getProperty("user.dir"), "..", "..", "contracts", relative)
            .toAbsolutePath()
            .normalize();
    }
}
