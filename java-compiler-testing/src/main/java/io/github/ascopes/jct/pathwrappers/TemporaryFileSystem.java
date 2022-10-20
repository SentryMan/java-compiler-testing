/*
 * Copyright (C) 2022 - 2022 Ashley Scopes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ascopes.jct.pathwrappers;

import static io.github.ascopes.jct.utils.IoExceptionUtils.uncheckedIo;
import static java.util.Objects.requireNonNull;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Feature;
import com.google.common.jimfs.Jimfs;
import com.google.common.jimfs.PathType;
import io.github.ascopes.jct.annotations.Nullable;
import io.github.ascopes.jct.utils.AsyncResourceCloser;
import io.github.ascopes.jct.utils.ToStringBuilder;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapper around a temporary in-memory file system that exposes the root path of said file
 * system.
 *
 * <p>This provides a utility for constructing complex and dynamic directory tree structures
 * quickly and simply using fluent chained methods.
 *
 * <p>These file systems are integrated into the {@link FileSystem} API, and can be configured to
 * automatically destroy themselves once this RamPath handle is garbage collected.
 *
 * <p>In addition, these paths follow POSIX file system semantics, meaning that files are handled
 * with case-sensitive names, and use forward slashes to separate paths.
 *
 * <p>While this will create a global {@link FileSystem}, it is recommended that you only interact
 * with the file system via this class to prevent potentially confusing behaviour elsewhere.
 *
 * @author Ashley Scopes
 * @since 0.0.1
 */
@API(since = "0.0.1", status = Status.EXPERIMENTAL)
public final class TemporaryFileSystem implements PathWrapper {

  private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
  private static final Logger LOGGER = LoggerFactory.getLogger(TemporaryFileSystem.class);
  private static final Cleaner CLEANER = Cleaner.create();

  private final Path path;
  private final URI uri;
  private final String name;

  /**
   * Initialize this in-memory path.
   *
   * @param name                     the name of the file system to create.
   * @param closeOnGarbageCollection {@code true} to delegate
   */
  @SuppressWarnings("ThisEscapedInObjectConstruction")
  private TemporaryFileSystem(String name, boolean closeOnGarbageCollection) {
    this.name = requireNonNull(name, "name");

    var config = Configuration
        .builder(PathType.unix())
        .setSupportedFeatures(Feature.LINKS, Feature.SYMBOLIC_LINKS, Feature.FILE_CHANNEL)
        .setAttributeViews("basic", "posix")
        .setRoots("/")
        .setWorkingDirectory("/")
        .setPathEqualityUsesCanonicalForm(true)
        .build();

    var fileSystem = Jimfs.newFileSystem(config);
    path = fileSystem.getRootDirectories().iterator().next().resolve(name);
    uri = path.toUri();

    // Ensure the base directory exists.
    uncheckedIo(() -> Files.createDirectories(path));

    if (closeOnGarbageCollection) {
      CLEANER.register(this, new AsyncResourceCloser(name, fileSystem));
    }

    LOGGER.trace("Initialized new in-memory directory {}", path);
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code null} in all cases. This implementation cannot have a parent path.
   */
  @Nullable
  @Override
  public PathWrapper getParent() {
    return null;
  }

  @Override
  public Path getPath() {
    return path;
  }

  @Override
  public URI getUri() {
    return uri;
  }

  /**
   * Get the identifying name of the temporary file system.
   *
   * @return the identifier string.
   */
  public String getName() {
    return name;
  }

  /**
   * Close the underlying file system.
   *
   * @throws UncheckedIOException if an IO error occurs.
   */
  public void close() {
    uncheckedIo(path.getFileSystem()::close);
  }

  /**
   * Create a file with the given content.
   *
   * @param filePath the path to the file.
   * @param content  the content.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs
   */
  public TemporaryFileSystem createFile(Path filePath, byte[] content) {
    var inputStream = new ByteArrayInputStream(content);
    return copyFrom(inputStream, filePath);
  }

  /**
   * Create a file with the given lines of text.
   *
   * <p>These lines will be separated with a {@code LINE FEED} character.
   *
   * @param filePath the path to the file.
   * @param lines    the lines of text to store.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs
   */
  public TemporaryFileSystem createFile(Path filePath, String... lines) {
    var content = String.join("\n", lines).getBytes(DEFAULT_CHARSET);
    return createFile(filePath, content);
  }

  /**
   * Create a file with the given content.
   *
   * @param fileName the path to the file.
   * @param content  the content.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs
   */
  public TemporaryFileSystem createFile(String fileName, byte[] content) {
    return createFile(path.resolve(fileName), content);
  }

  /**
   * Create a file with the given lines of text.
   *
   * <p>These lines will be separated with a {@code LINE FEED} character.
   *
   * @param fileName the path to the file.
   * @param lines    the lines of text to store.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs
   */
  public TemporaryFileSystem createFile(String fileName, String... lines) {
    return createFile(path.resolve(fileName), lines);
  }

  /**
   * Copy a resource from the classpath into this in-memory path at the target location.
   *
   * @param resource   the name of the classpath resource.
   * @param targetPath the path to store the resource within this location.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFromClassPath(String resource, Path targetPath) {
    return copyFromClassPath(currentCallerClassLoader(), resource, targetPath);
  }

  /**
   * Copy a resource from the classpath into this in-memory path at the target location.
   *
   * @param resource   the name of the classpath resource.
   * @param targetPath the path to store the resource within this location.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFromClassPath(String resource, String targetPath) {
    return copyFromClassPath(currentCallerClassLoader(), resource, targetPath);
  }

  /**
   * Copy a resource from the classpath into this in-memory path at the target location.
   *
   * @param loader     the classloader to load from.
   * @param resource   the name of the classpath resource.
   * @param targetPath the path to store the resource within this location.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFromClassPath(
      ClassLoader loader,
      String resource,
      Path targetPath
  ) {
    return uncheckedIo(() -> {
      var input = loader.getResourceAsStream(resource);
      if (input == null) {
        throw new FileNotFoundException("classpath:" + resource);
      }
      try (input) {
        return copyFrom(input, targetPath);
      }
    });
  }

  /**
   * Copy a resource from the classpath into this in-memory path at the target location.
   *
   * @param loader     the classloader to load from.
   * @param resource   the name of the classpath resource.
   * @param targetPath the path to store the resource within this location.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFromClassPath(
      ClassLoader loader,
      String resource,
      String targetPath
  ) {
    return copyFromClassPath(loader, resource, path.resolve(targetPath));
  }

  /**
   * Copy an existing path into this location.
   *
   * @param existingFile the existing file.
   * @param targetPath   the path in this location to copy the file to.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFrom(Path existingFile, Path targetPath) {
    return uncheckedIo(() -> {
      try (var input = Files.newInputStream(existingFile)) {
        return copyFrom(input, targetPath);
      }
    });
  }

  /**
   * Copy an existing path into this location.
   *
   * @param existingFile the existing file.
   * @param targetPath   the path in this location to copy the file to.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFrom(Path existingFile, String targetPath) {
    return copyFrom(existingFile, path.resolve(targetPath));
  }

  /**
   * Copy an existing resource at a given URL into this location.
   *
   * @param url        the URL of the resource to copy.
   * @param targetPath the path in this location to copy the file to.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFrom(URL url, Path targetPath) {
    return uncheckedIo(() -> {
      try (var input = url.openStream()) {
        return copyFrom(input, targetPath);
      }
    });
  }

  /**
   * Copy an existing resource at a given URL into this location.
   *
   * @param url        the URL of the resource to copy.
   * @param targetPath the path in this location to copy the file to.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFrom(URL url, String targetPath) {
    return uncheckedIo(() -> {
      try (var input = url.openStream()) {
        return copyFrom(input, targetPath);
      }
    });
  }

  /**
   * Copy an existing resource from an input stream into this location.
   *
   * @param input      the input stream to use.
   * @param targetPath the path in this location to copy the file to.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFrom(InputStream input, String targetPath) {
    return copyFrom(input, path.resolve(targetPath));
  }

  /**
   * Copy an existing resource from an input stream into this location.
   *
   * @param input      the input stream to use.
   * @param targetPath the path in this location to copy the file to.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyFrom(InputStream input, Path targetPath) {
    return uncheckedIo(() -> {
      var bufferedInput = maybeBuffer(input, targetPath.toUri().getScheme());
      var path = makeRelativeToHere(targetPath);
      var options = new OpenOption[]{
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
      };
      Files.createDirectories(path.getParent());

      // TODO(ascopes): should this be a buffered output?
      try (var output = Files.newOutputStream(path, options)) {
        bufferedInput.transferTo(output);
      }
      return this;
    });
  }

  /**
   * Copy the contents of the given file tree to the given target path.
   *
   * @param tree       the tree to copy.
   * @param targetPath the target path to copy everything into.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyTreeFrom(Path tree, Path targetPath) {
    return uncheckedIo(() -> {
      var relativeTargetPath = makeRelativeToHere(targetPath);

      Files.walkFileTree(tree, new SimpleFileVisitor<>() {

        @Override
        public FileVisitResult preVisitDirectory(
            Path dir,
            BasicFileAttributes attrs
        ) throws IOException {

          var targetChildDirectory = relativeTargetPath.resolve(tree.relativize(dir).toString());
          // Ignore if the directory already exists (will occur for the root).
          Files.createDirectories(targetChildDirectory);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(
            Path file,
            BasicFileAttributes attrs
        ) throws IOException {

          var targetFile = relativeTargetPath.resolve(tree.relativize(file).toString());
          Files.copy(file, targetFile);
          return FileVisitResult.CONTINUE;
        }
      });

      return this;
    });
  }

  /**
   * Copy the contents of the given file tree to the given target path.
   *
   * @param tree       the tree to copy.
   * @param targetPath the target path to copy everything into.
   * @return this object for further call chaining.
   * @throws UncheckedIOException if an IO error occurs.
   */
  public TemporaryFileSystem copyTreeFrom(Path tree, String targetPath) {
    return copyTreeFrom(tree, path.resolve(targetPath));
  }

  /**
   * Copy the entire tree to a temporary directory on the default file system so that it can be
   * inspected manually in a file explorer.
   *
   * <p>You must then delete the created directory manually.
   *
   * @return the path to the temporary directory that was created.
   * @throws UncheckedIOException if something goes wrong copying the tree out of memory.
   */
  public Path copyToTempDir() {
    // https://find-sec-bugs.github.io/bugs.htm#PATH_TRAVERSAL_IN
    var safeName = name.substring(Math.max(0, name.lastIndexOf('/')));

    var tempPath = uncheckedIo(() -> Files.copy(
        path,
        Files.createTempDirectory(safeName),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES
    ));

    LOGGER.info("Copied {} into temporary directory on file system at {}", uri, tempPath);

    return tempPath;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof TemporaryFileSystem)) {
      return false;
    }

    var that = (TemporaryFileSystem) other;

    return name.equals(that.name)
        && uri.equals(that.uri);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, uri);
  }

  @Override
  public String toString() {
    return new ToStringBuilder(this)
        .attribute("name", name)
        .attribute("uri", uri)
        .toString();
  }

  private Path makeRelativeToHere(Path relativePath) {
    if (relativePath.isAbsolute() && !relativePath.startsWith(path)) {
      var fixedPath = relativePath.getRoot().relativize(relativePath);

      LOGGER.warn(
          "Treating {} as relative path {} (hint: consider removing the leading forward-slash)",
          relativePath,
          fixedPath
      );

      relativePath = fixedPath;
    }

    // ToString is needed as JIMFS will fail on trying to make a relative path from a different
    // provider.
    return relativePath.getFileSystem() == path.getFileSystem()
        ? relativePath.normalize()
        : path.resolve(relativePath.toString()).normalize();
  }

  /**
   * Create a new in-memory path.
   *
   * <p>The underlying in-memory file system will be closed and destroyed when the returned
   * object is garbage collected, or when {@link #close()} is called on it manually.
   *
   * @param name a symbolic name to give the path. This must be a valid POSIX directory name.
   * @return the in-memory path.
   * @see #named(String, boolean)
   */
  public static TemporaryFileSystem named(String name) {
    return named(name, true);
  }

  /**
   * Create a new in-memory path.
   *
   * @param name                     a symbolic name to give the path. This must be a valid POSIX
   *                                 directory name.
   * @param closeOnGarbageCollection if {@code true}, then the {@link #close()} operation will be
   *                                 called on the underlying {@link FileSystem} as soon as the
   *                                 returned object from this method is garbage collected. If
   *                                 {@code false}, then you must close the underlying file system
   *                                 manually using the {@link #close()} method on the returned
   *                                 object. Failing to do so will lead to resources being leaked.
   * @return the in-memory path.
   * @see #named(String)
   */
  public static TemporaryFileSystem named(String name, boolean closeOnGarbageCollection) {
    return new TemporaryFileSystem(name, closeOnGarbageCollection);
  }

  private static InputStream maybeBuffer(InputStream input, String scheme) {
    if (input instanceof BufferedInputStream || input instanceof ByteArrayInputStream) {
      return input;
    }

    scheme = scheme == null
        ? "unknown"
        : scheme.toLowerCase(Locale.ENGLISH);

    switch (scheme) {
      case "classpath":
      case "jimfs":
      case "jrt":
      case "ram":
        return input;
      default:
        LOGGER.trace("Decided to wrap input {} in a buffer", input);
        return new BufferedInputStream(input);
    }
  }

  private static ClassLoader currentCallerClassLoader() {
    return Thread.currentThread().getContextClassLoader();
  }
}