package org.zalava.modules.filesystem;

import org.zalava.ModuleDescriptor;
import org.zalava.ModuleConfigurationDescriptor;
import org.zalava.ProviderFactory;
import org.zalava.SeaModule;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Service-loaded SEA module for provider-scoped filesystem access. */
public final class FileSystemSeaModule implements SeaModule {

    public static final String MODULE_ID = "zalava-module-filesystem";

    @Override
    public ModuleDescriptor descriptor() {
        return new ModuleDescriptor(
                MODULE_ID,
                version(),
                "Scoped Filesystem",
                "SEA filesystem providers constrained to configured roots."
        );
    }

    @Override
    public List<ProviderFactory> providerFactories() {
        return List.of(new FileSystemProviderFactory());
    }

    @Override
    public ModuleConfigurationDescriptor configuration() {
        return new ModuleConfigurationDescriptor(Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("filesystem-root", Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "required", List.of("roots"),
                        "properties", Map.of("roots", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "items", Map.of(
                                        "type", "object",
                                        "additionalProperties", false,
                                        "required", List.of("id", "displayName", "path", "writable"),
                                        "properties", Map.of(
                                                "id", Map.of("type", "string", "minLength", 1),
                                                "displayName", Map.of("type", "string", "minLength", 1),
                                                "path", Map.of("type", "string", "minLength", 1),
                                                "writable", Map.of("type", "boolean")
                                        )
                                )
                        ))
                ))
        ));
    }

    static String version() {
        Properties properties = new Properties();
        try (InputStream input = FileSystemSeaModule.class.getResourceAsStream("/module.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing module version metadata");
            }
            properties.load(input);
            return properties.getProperty("module.version");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read module version metadata", exception);
        }
    }
}
