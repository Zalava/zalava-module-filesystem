package org.zalava.modules.filesystem;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.zalava.api.ProviderFactory;
import org.zalava.api.ProviderFactoryContext;
import org.zalava.api.ProviderFactoryDescriptor;
import org.zalava.api.ZalavaProvider;

/** Creates providers only from configuration explicitly scoped to this factory. */
public final class FileSystemProviderFactory implements ProviderFactory {

  public static final String FACTORY_ID = "filesystem-root";

  private static final ProviderFactoryDescriptor DESCRIPTOR =
      new ProviderFactoryDescriptor(
          FACTORY_ID,
          FileSystemSeaModule.MODULE_ID,
          "filesystem-root",
          "Scoped Filesystem Root Factory",
          "Creates filesystem providers constrained to declared roots.");

  @Override
  public ProviderFactoryDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public List<ZalavaProvider> createProviders(ProviderFactoryContext context) {
    Object configuredRoots = context.configuration().get("roots");
    if (configuredRoots == null) {
      return List.of();
    }
    if (!(configuredRoots instanceof List<?> roots)) {
      throw new IllegalArgumentException("Filesystem factory roots must be a list");
    }
    return roots.stream()
        .map(this::root)
        .map(FileSystemSeaProvider::new)
        .map(ZalavaProvider.class::cast)
        .toList();
  }

  private FileSystemRoot root(Object value) {
    if (!(value instanceof Map<?, ?> root)) {
      throw new IllegalArgumentException("Filesystem root must be an object");
    }
    Object rawPath = root.get("path");
    if (!(rawPath instanceof String path) || path.isBlank()) {
      throw new IllegalArgumentException("Filesystem root path is required");
    }
    Object rawWritable = root.get("writable");
    boolean writable =
        rawWritable == null || rawWritable instanceof Boolean && (Boolean) rawWritable;
    if (rawWritable != null && !(rawWritable instanceof Boolean)) {
      throw new IllegalArgumentException("Filesystem root writable must be a boolean");
    }
    return new FileSystemRoot(text(root, "id"), text(root, "displayName"), Path.of(path), writable);
  }

  private static String text(Map<?, ?> values, String key) {
    Object value = values.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("Filesystem root " + key + " is required");
    }
    return text;
  }
}
