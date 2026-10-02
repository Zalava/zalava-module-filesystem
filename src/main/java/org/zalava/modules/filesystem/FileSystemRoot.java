package org.zalava.modules.filesystem;

import java.nio.file.Path;

record FileSystemRoot(String id, String displayName, Path path, boolean writable) {

  FileSystemRoot {
    requireText(id, "Filesystem root id");
    requireText(displayName, "Filesystem root display name");
    if (path == null) {
      throw new IllegalArgumentException("Filesystem root path is required");
    }
    path = path.toAbsolutePath().normalize();
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
