package org.zalava.modules.filesystem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.zalava.InvocationContext;
import org.zalava.ProviderCapabilities;
import org.zalava.ProviderDescriptor;
import org.zalava.ResourceDescriptor;
import org.zalava.ZalavaOperationResult;
import org.zalava.ZalavaProvider;
import org.zalava.ZalavaToolDescriptor;
import org.zalava.ZalavaToolInputSchemas;
import tools.jackson.databind.JsonNode;

final class FileSystemSeaProvider implements ZalavaProvider {

  private static final List<ZalavaToolDescriptor> TOOLS =
      List.of(
          tool(
              "listDirectory",
              "List entries in a directory under this provider root.",
              false,
              Map.of("path", ZalavaToolInputSchemas.string()),
              "path"),
          tool(
              "readFile",
              "Read a UTF-8 text file under this provider root.",
              false,
              Map.of("path", ZalavaToolInputSchemas.string()),
              "path"),
          tool(
              "writeFile",
              "Write a UTF-8 text file under this provider root.",
              true,
              Map.of(
                  "path",
                  ZalavaToolInputSchemas.string(),
                  "content",
                  ZalavaToolInputSchemas.string()),
              "path"),
          tool(
              "Read",
              "Read a file under this provider root with optional line bounds.",
              false,
              Map.of(
                  "filePath",
                  ZalavaToolInputSchemas.string(),
                  "offset",
                  ZalavaToolInputSchemas.integer(),
                  "limit",
                  ZalavaToolInputSchemas.integer()),
              "filePath"),
          tool(
              "Write",
              "Write a UTF-8 file under this provider root.",
              true,
              Map.of(
                  "filePath",
                  ZalavaToolInputSchemas.string(),
                  "content",
                  ZalavaToolInputSchemas.string()),
              "filePath"),
          tool(
              "Edit",
              "Replace text in a file under this provider root.",
              true,
              Map.of(
                  "filePath",
                  ZalavaToolInputSchemas.string(),
                  "old_string",
                  ZalavaToolInputSchemas.string(),
                  "new_string",
                  ZalavaToolInputSchemas.string(),
                  "replace_all",
                  ZalavaToolInputSchemas.bool()),
              "filePath",
              "old_string",
              "new_string"));

  private final FileSystemRoot root;
  private final Path rootPath;
  private final ProviderDescriptor descriptor;

  FileSystemSeaProvider(FileSystemRoot root) {
    this.root = root;
    try {
      Files.createDirectories(root.path());
      this.rootPath = root.path().toRealPath();
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Unable to create filesystem root: " + root.path(), exception);
    }
    this.descriptor =
        new ProviderDescriptor(
            "filesystem-" + root.id(),
            FileSystemSeaModule.MODULE_ID,
            "filesystem-root",
            root.displayName(),
            "Provider-scoped filesystem access rooted at " + rootPath,
            FileSystemSeaModule.version(),
            new ProviderCapabilities(true, true, false, false, false, false, false, false),
            root.writable()
                ? List.of("sea_backed", "filesystem", "writable")
                : List.of("sea_backed", "filesystem", "read_only"),
            Map.of("root", rootPath.toString(), "writable", Boolean.toString(root.writable())));
  }

  @Override
  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ProviderCapabilities capabilities() {
    return descriptor.capabilities();
  }

  @Override
  public List<ZalavaToolDescriptor> listTools() {
    return root.writable() ? TOOLS : TOOLS.stream().filter(tool -> !tool.sideEffecting()).toList();
  }

  @Override
  public ZalavaOperationResult callTool(
      String toolName, JsonNode arguments, InvocationContext context) {
    return switch (toolName) {
      case "listDirectory" -> listDirectory(required(arguments, "path"));
      case "readFile" -> readFile(required(arguments, "path"));
      case "writeFile" ->
          writeFile(required(arguments, "path"), arguments.path("content").asText(""), context);
      case "Read" -> read(arguments);
      case "Write" -> write(arguments, context);
      case "Edit" -> edit(arguments, context);
      default -> throw new IllegalArgumentException("Unknown filesystem tool: " + toolName);
    };
  }

  @Override
  public List<ResourceDescriptor> listResources() {
    return List.of(new ResourceDescriptor(resourcePrefix(), descriptor.description()));
  }

  @Override
  public ZalavaOperationResult readResource(String uri, InvocationContext context) {
    if (uri == null || !uri.startsWith(resourcePrefix())) {
      throw new IllegalArgumentException("Unsupported filesystem resource uri: " + uri);
    }
    return readFile(uri.substring(resourcePrefix().length()));
  }

  private ZalavaOperationResult listDirectory(String path) {
    Path directory = existingPath(path);
    if (!Files.isDirectory(directory)) {
      throw new IllegalArgumentException("Path is not a directory: " + path);
    }
    try (var entries = Files.list(directory)) {
      return result(
          Map.of(
              "path",
              path,
              "entries",
              entries.map(entry -> entry.getFileName().toString()).sorted().toList()));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to list directory: " + path, exception);
    }
  }

  private ZalavaOperationResult readFile(String path) {
    Path file = existingPath(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("Path is not a regular file: " + path);
    }
    try {
      return result(
          Map.of("path", path, "content", Files.readString(file, StandardCharsets.UTF_8)));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read file: " + path, exception);
    }
  }

  private ZalavaOperationResult writeFile(String path, String content, InvocationContext context) {
    requireWritableAndConfirmed(path, context, "write");
    Path file = writablePath(path);
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8);
      return result(Map.of("path", path, "bytes", content.getBytes(StandardCharsets.UTF_8).length));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to write file: " + path, exception);
    }
  }

  private ZalavaOperationResult read(JsonNode arguments) {
    String path = requiredFilePath(arguments);
    Path file = existingPath(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("Path is not a regular file: " + path);
    }
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      int offset = optionalPositiveInt(arguments.path("offset"), 1, "offset");
      int limit = optionalPositiveInt(arguments.path("limit"), 2_000, "limit");
      int start = Math.min(offset - 1, lines.size());
      int end = Math.min(start + limit, lines.size());
      List<String> rendered = new ArrayList<>();
      for (int index = start; index < end; index++) {
        rendered.add("%6d\t%s".formatted(index + 1, truncate(lines.get(index))));
      }
      String header =
          start == end
              ? "File has no lines in the requested range"
              : "Showing lines %d-%d of %d".formatted(start + 1, end, lines.size());
      return result(Map.of("path", path, "result", header + "\n" + String.join("\n", rendered)));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read file: " + path, exception);
    }
  }

  private ZalavaOperationResult write(JsonNode arguments, InvocationContext context) {
    String path = requiredFilePath(arguments);
    requireWritableAndConfirmed(path, context, "write");
    Path file = writablePath(path);
    String content = arguments.path("content").asText("");
    try {
      boolean existed = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
      Files.writeString(file, content, StandardCharsets.UTF_8);
      return result(
          Map.of(
              "path",
              path,
              "result",
              existed ? "Successfully overwrote file" : "Successfully created file"));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to write file: " + path, exception);
    }
  }

  private ZalavaOperationResult edit(JsonNode arguments, InvocationContext context) {
    String path = requiredFilePath(arguments);
    requireWritableAndConfirmed(path, context, "edit");
    String oldValue = required(arguments, "old_string");
    String newValue = required(arguments, "new_string");
    Path file = existingPath(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("Path is not a regular file: " + path);
    }
    try {
      String current = Files.readString(file, StandardCharsets.UTF_8);
      if (!current.contains(oldValue)) {
        throw new IllegalArgumentException("Text to replace was not found: " + path);
      }
      boolean replaceAll = arguments.path("replace_all").asBoolean(false);
      Files.writeString(
          file,
          replaceAll
              ? current.replace(oldValue, newValue)
              : current.replaceFirst(
                  java.util.regex.Pattern.quote(oldValue),
                  java.util.regex.Matcher.quoteReplacement(newValue)),
          StandardCharsets.UTF_8);
      return result(Map.of("path", path, "result", "File has been updated"));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to edit file: " + path, exception);
    }
  }

  private Path existingPath(String path) {
    Path resolved = resolveRelative(path);
    if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Path does not exist: " + path);
    }
    try {
      Path realPath = resolved.toRealPath();
      ensureContained(realPath, path);
      return realPath;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to resolve path: " + path, exception);
    }
  }

  private Path writablePath(String path) {
    Path resolved = resolveRelative(path);
    Path parent = resolved.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Path has no parent: " + path);
    }
    try {
      Files.createDirectories(parent);
      ensureContained(parent.toRealPath(), path);
      if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
        ensureContained(resolved.toRealPath(), path);
      }
      return resolved;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to resolve writable path: " + path, exception);
    }
  }

  private Path resolveRelative(String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("Path is required");
    }
    Path relative;
    try {
      relative = Path.of(path).normalize();
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Invalid path: " + path, exception);
    }
    if (relative.isAbsolute()) {
      throw new IllegalArgumentException("Path must be relative: " + path);
    }
    Path resolved = rootPath.resolve(relative).normalize();
    ensureContained(resolved, path);
    return resolved;
  }

  private void requireWritableAndConfirmed(String path, InvocationContext context, String action) {
    if (!root.writable()) {
      throw new UnsupportedOperationException(
          "Filesystem provider is read-only: " + descriptor.providerId());
    }
    if (context == null || !context.confirmed()) {
      throw new IllegalArgumentException(
          "Filesystem " + action + " requires confirmation: " + path);
    }
  }

  private void ensureContained(Path path, String input) {
    if (!path.startsWith(rootPath)) {
      throw new IllegalArgumentException("Path escapes provider root: " + input);
    }
  }

  private ZalavaOperationResult result(Map<String, Object> content) {
    return new ZalavaOperationResult(
        true,
        content,
        Map.of(
            "providerId",
            descriptor.providerId(),
            "root",
            rootPath.toString(),
            "writable",
            root.writable()));
  }

  private String resourcePrefix() {
    return "filesystem://" + root.id() + "/";
  }

  private static String required(JsonNode arguments, String field) {
    String value = arguments.path(field).asText(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String requiredFilePath(JsonNode arguments) {
    String filePath = arguments.path("filePath").asText(null);
    return filePath == null || filePath.isBlank() ? required(arguments, "file_path") : filePath;
  }

  private static int optionalPositiveInt(JsonNode value, int defaultValue, String field) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return defaultValue;
    }
    int parsed = value.asInt(-1);
    if (parsed < 1) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return parsed;
  }

  private static String truncate(String line) {
    return line.length() > 2_000 ? line.substring(0, 2_000) + " (line truncated)" : line;
  }

  private static ZalavaToolDescriptor tool(
      String name,
      String description,
      boolean sideEffecting,
      Map<String, Object> properties,
      String... required) {
    return new ZalavaToolDescriptor(
        name,
        description,
        sideEffecting,
        List.of(),
        ZalavaToolInputSchemas.object(properties, required));
  }
}
