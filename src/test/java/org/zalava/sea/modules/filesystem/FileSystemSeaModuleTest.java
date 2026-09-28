package org.zalava.modules.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.zalava.InvocationContext;
import org.zalava.SeaOperationResult;
import org.zalava.SeaProvider;
import org.zalava.SeaToolDescriptor;
import org.zalava.testing.ConfigFixture;
import org.zalava.testing.ModuleContractKit;
import org.zalava.testing.ProviderFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises the real built module JAR at the stable {@code module-api} boundary through the released
 * contract kit. Host-owned resolution, validation, permissions and persistence stay covered by SEA.
 */
class FileSystemSeaModuleTest {

    private static final String MODULE_ID = "zalava-module-filesystem";
    private static final String FACTORY_ID = "filesystem-root";
    private static final String PROVIDER_ID = "filesystem-workspace";

    @TempDir
    Path workspace;

    private ModuleContractKit kit;

    @BeforeEach
    void loadTheBuiltArtifact() {
        Path artifact = Path.of(System.getProperty("module.artifact"));
        String version = System.getProperty("module.version");
        kit = ModuleContractKit.load(artifact, List.of(), MODULE_ID, version);
    }

    @AfterEach
    void closeTheArtifact() throws Exception {
        if (kit != null) {
            kit.close();
        }
    }

    @Test
    void loadsTheModuleFromTheBuiltArtifact() {
        assertThat(kit.module().getClass().getClassLoader()).isNotSameAs(getClass().getClassLoader());
        assertThat(kit.module().getClass().getProtectionDomain().getCodeSource().getLocation().toString())
                .endsWith(".jar");
    }

    @Test
    void exposesTheModuleOwnedDescriptorAndConfigurationContract() {
        assertThat(kit.moduleId()).isEqualTo(MODULE_ID);
        assertThat(kit.version()).isEqualTo(System.getProperty("module.version"));

        Map<String, Object> schema = kit.module().configuration().jsonSchema();
        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        Object properties = schema.get("properties");
        assertThat(properties).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) properties).containsKey("filesystem-root")).isTrue();
    }

    @Test
    void createsNoProviderUntilHostConfiguresRoots() {
        try (ProviderFixture providers = kit.providers(ConfigFixture.empty())) {
            assertThat(providers.providers()).isEmpty();
        }
    }

    @Test
    void createsTheConfiguredProviderAndDeclaresItsTools() {
        try (ProviderFixture providers = kit.providers(configuration(workspace, true))) {
            SeaProvider provider = providers.requireProvider(PROVIDER_ID);

            assertThat(provider.descriptor().moduleId()).isEqualTo(MODULE_ID);
            assertThat(provider.listTools().stream().map(SeaToolDescriptor::name))
                    .containsExactly("listDirectory", "readFile", "writeFile", "Read", "Write", "Edit");
        }
    }

    @Test
    void writesReadsListsAndReadsResourcesInsideTheRoot() throws Exception {
        try (ProviderFixture providers = kit.providers(configuration(workspace, true))) {
            SeaOperationResult write = providers.invoke(PROVIDER_ID, "writeFile",
                    arguments().put("path", "notes/hello.txt").put("content", "hello sea"), confirmed());
            assertThat(write.success()).isTrue();

            assertThat(content(providers.invoke(PROVIDER_ID, "readFile",
                    arguments().put("path", "notes/hello.txt"))).get("content")).isEqualTo("hello sea");
            assertThat(content(providers.invoke(PROVIDER_ID, "listDirectory",
                    arguments().put("path", "notes"))).get("entries")).isEqualTo(List.of("hello.txt"));

            SeaProvider provider = providers.requireProvider(PROVIDER_ID);
            assertThat(content(provider.readResource("filesystem://workspace/notes/hello.txt", InvocationContext.system())).get("content"))
                    .isEqualTo("hello sea");
        }
    }

    @Test
    void appliesTheReadWriteAndEditCompatibilityTools() throws Exception {
        try (ProviderFixture providers = kit.providers(configuration(workspace, true))) {
            providers.invoke(PROVIDER_ID, "Write",
                    arguments().put("file_path", "notes/community.txt").put("content", "alpha\nbeta\nalpha"), confirmed());
            Object read = content(providers.invoke(PROVIDER_ID, "Read",
                    arguments().put("file_path", "notes/community.txt").put("offset", 2).put("limit", 1))).get("result");
            providers.invoke(PROVIDER_ID, "Edit",
                    arguments().put("file_path", "notes/community.txt")
                            .put("old_string", "alpha").put("new_string", "changed").put("replace_all", true), confirmed());

            assertThat(read.toString()).contains("Showing lines 2-2 of 3");
            assertThat(Files.readString(workspace.resolve("notes/community.txt"))).isEqualTo("changed\nbeta\nchanged");
        }
    }

    @Test
    void rejectsTraversalAndSymlinkEscapes() throws Exception {
        try (ProviderFixture providers = kit.providers(configuration(workspace, true))) {
            Path outside = Files.createTempFile("sea-outside", ".txt");
            Files.writeString(outside, "outside");
            Files.createSymbolicLink(workspace.resolve("outside-link"), outside);

            assertThatThrownBy(() -> providers.invoke(PROVIDER_ID, "readFile",
                    arguments().put("path", "../outside.txt")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("escapes provider root");
            assertThatThrownBy(() -> providers.invoke(PROVIDER_ID, "readFile",
                    arguments().put("path", "outside-link")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("escapes provider root");
        }
    }

    @Test
    void deniesReadOnlyAndUnconfirmedMutations() throws Exception {
        try (ProviderFixture providers = kit.providers(configuration(workspace, false))) {
            assertThat(providers.tools(PROVIDER_ID).stream().map(SeaToolDescriptor::name))
                    .containsExactly("listDirectory", "readFile", "Read");
            SeaProvider readOnly = providers.requireProvider(PROVIDER_ID);
            assertThatThrownBy(() -> readOnly.callTool("writeFile",
                    arguments().put("path", "no.txt").put("content", "no"), confirmed()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("read-only");
        }

        try (ProviderFixture providers = kit.providers(configuration(workspace, true))) {
            assertThatThrownBy(() -> providers.invoke(PROVIDER_ID, "Write",
                    arguments().put("file_path", "no.txt").put("content", "no")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires confirmation");
            assertThat(Files.exists(workspace.resolve("no.txt"))).isFalse();
        }
    }

    private static ConfigFixture configuration(Path root, boolean writable) {
        return ConfigFixture.empty().factoryConfiguration(MODULE_ID, FACTORY_ID, Map.of(
                "roots", List.of(Map.of(
                        "id", "workspace",
                        "displayName", "Workspace Filesystem",
                        "path", root.toString(),
                        "writable", writable))));
    }

    private static ObjectNode arguments() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static InvocationContext confirmed() {
        return new InvocationContext("test", true, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(SeaOperationResult result) {
        return (Map<String, Object>) result.content();
    }
}