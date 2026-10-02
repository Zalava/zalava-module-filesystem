package org.zalava.modules.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zalava.api.InvocationContext;
import org.zalava.api.ProviderFactoryContext;
import tools.jackson.databind.json.JsonMapper;

class FileSystemBoundaryTest {
  @TempDir Path directory;
  private final JsonMapper json = new JsonMapper();
  private final InvocationContext confirmed = new InvocationContext("fixture", true, Map.of());

  @Test
  void rejectsMalformedRootConfiguration() {
    var factory = new FileSystemProviderFactory();
    assertThat(factory.createProviders(new ProviderFactoryContext(Map.of()))).isEmpty();
    for (Object roots :
        List.of(
            "invalid",
            List.of("invalid"),
            List.of(Map.of("path", " ")),
            List.of(Map.of("path", directory.toString(), "writable", "true")),
            List.of(Map.of("path", directory.toString())),
            List.of(Map.of("path", directory.toString(), "id", "id", "displayName", " ")))) {
      assertThatThrownBy(
              () -> factory.createProviders(new ProviderFactoryContext(Map.of("roots", roots))))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new FileSystemRoot("id", "name", null, true))
        .hasMessageContaining("path");
    assertThatThrownBy(() -> new FileSystemRoot(" ", "name", directory, true))
        .hasMessageContaining("id");
    assertThatThrownBy(() -> new FileSystemRoot("id", null, directory, true))
        .hasMessageContaining("display name");
  }

  @Test
  void validatesOperationPathsRangesAndConfirmation() throws Exception {
    var provider = provider(true);
    assertThat(provider.capabilities().supportsResources()).isTrue();
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "unknown",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            json.createObjectNode(),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed))
        .hasMessageContaining("Unknown filesystem tool");
    for (String uri : new String[] {null, "https://outside/file"}) {
      assertThatThrownBy(() -> provider.readResource(uri, confirmed))
          .hasMessageContaining("Unsupported");
    }
    Files.writeString(directory.resolve("file"), "a\nb\n" + "x".repeat(2001));
    Files.createDirectory(directory.resolve("folder"));
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "listDirectory",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("path", "file"),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed))
        .hasMessageContaining("directory");
    for (String tool : List.of("readFile", "Read", "Edit")) {
      var arguments =
          args("path", "folder")
              .put("filePath", "folder")
              .put("old_string", "a")
              .put("new_string", "b");
      assertThatThrownBy(
              () ->
                  provider.callTool(
                      tool,
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments,
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}),
                      confirmed))
          .hasMessageContaining("regular file");
    }
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "readFile",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("path", "missing"),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed))
        .hasMessageContaining("does not exist");
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "readFile",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("path", directory.toString()),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed))
        .hasMessageContaining("relative");
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "readFile",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("path", "\u0000"),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed))
        .hasMessageContaining("Invalid");
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "Read",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("filePath", "file").put("offset", 0),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed))
        .hasMessageContaining("positive");
    assertThat(
            provider
                .callTool(
                    "Read",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("file_path", "file").put("offset", 100),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed)
                .content()
                .toString())
        .contains("File has no lines in the requested range");
    assertThat(
            provider
                .callTool(
                    "Read",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("filePath", "file").putNull("limit"),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed)
                .content()
                .toString())
        .contains("line truncated");
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "Write",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("filePath", "file"),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    null))
        .hasMessageContaining("confirmation");
    assertThatThrownBy(
            () ->
                provider(false)
                    .callTool(
                        "Write",
                        new tools.jackson.databind.json.JsonMapper()
                            .convertValue(
                                args("filePath", "file"),
                                new tools.jackson.core.type.TypeReference<
                                    java.util.Map<String, Object>>() {}),
                        confirmed))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "Edit",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("filePath", "file")
                                .put("old_string", "missing")
                                .put("new_string", "b"),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed))
        .hasMessageContaining("not found");
    assertThat(
            provider
                .callTool(
                    "Write",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("filePath", "new").put("content", "created"),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed)
                .content()
                .toString())
        .contains("created");
    assertThat(
            provider
                .callTool(
                    "Write",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("filePath", "new").put("content", "replaced"),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed)
                .content()
                .toString())
        .contains("overwrote");
    assertThat(Files.readString(directory.resolve("new"))).isEqualTo("replaced");
  }

  @Test
  void surfacesFilesystemIoFailuresWithoutEscapingItsRoot() throws Exception {
    var provider = provider(true);
    Files.write(directory.resolve("invalid-utf8"), new byte[] {(byte) 0xc0});
    for (String tool : List.of("readFile", "Read", "Edit")) {
      var arguments =
          args("path", "invalid-utf8")
              .put("filePath", "invalid-utf8")
              .put("old_string", "x")
              .put("new_string", "y");
      assertThatThrownBy(
              () ->
                  provider.callTool(
                      tool,
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments,
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}),
                      confirmed))
          .isInstanceOf(IllegalStateException.class);
    }
    Files.createDirectory(directory.resolve("folder"));
    for (String tool : List.of("Write", "writeFile")) {
      assertThatThrownBy(
              () ->
                  provider.callTool(
                      tool,
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              args("path", "folder").put("filePath", "folder"),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}),
                      confirmed))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                provider.callTool(
                    "Write",
                    new tools.jackson.databind.json.JsonMapper()
                        .convertValue(
                            args("filePath", "invalid-utf8/child"),
                            new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {}),
                    confirmed))
        .isInstanceOf(IllegalStateException.class);
    Path blocker = directory.resolve("blocker");
    Files.writeString(blocker, "fixture");
    assertThatThrownBy(
            () ->
                new FileSystemSeaProvider(new FileSystemRoot("invalid", "Invalid", blocker, true)))
        .hasMessageContaining("Unable to create filesystem root");
  }

  private FileSystemSeaProvider provider(boolean writable) {
    return new FileSystemSeaProvider(new FileSystemRoot("fixture", "Fixture", directory, writable));
  }

  private tools.jackson.databind.node.ObjectNode args(String key, String value) {
    return json.createObjectNode().put(key, value);
  }
}
