package com.github.ascopes.jct.paths;

import com.github.ascopes.jct.intern.Lazy;
import com.github.ascopes.jct.intern.StringSlicer;
import com.github.ascopes.jct.intern.StringUtils;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Predicate;
import javax.tools.FileObject;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;


/**
 * Manager of paths for a specific compiler location.
 *
 * <p>This provides access to file objects within any of the paths, as well as the ability
 * to construct classloaders for the paths as needed.
 *
 * @author Ashley Scopes
 * @since 0.0.1
 */
public class PackageOrientedPathLocationManager {

  private static final StringSlicer PACKAGE_SPLITTER = new StringSlicer(".");

  protected final Location location;
  protected final Set<Path> roots;
  private final Lazy<ClassLoader> classLoader;

  // We use this to keep the references alive while the manager is alive.
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private final Set<InMemoryPath> inMemoryDirectories;

  /**
   * Initialize the manager.
   *
   * @param location the location to represent.
   */
  public PackageOrientedPathLocationManager(Location location) {
    this.location = Objects.requireNonNull(location);
    roots = new LinkedHashSet<>();
    classLoader = new Lazy<>(this::createClassLoaderUnsafe);

    inMemoryDirectories = new HashSet<>();
  }

  /**
   * Add an in-memory directory to the manager, if it has not already been added.
   *
   * <p>This will destroy the existing classloader, if it already exists.
   *
   * @param inMemoryDirectory the path to add.
   */
  public void addPath(InMemoryPath inMemoryDirectory) {
    addPath(inMemoryDirectory.getPath());
    // Keep the reference alive.
    inMemoryDirectories.add(inMemoryDirectory);
  }

  /**
   * Add a path to the manager, if it has not already been added.
   *
   * <p>This will destroy the existing classloader, if it already exists.
   *
   * @param path the path to add.
   */
  public void addPath(Path path) {
    classLoader.destroy();
    roots.add(path.toAbsolutePath());
  }

  /**
   * Add multiple paths to the manager, if they have not already been added.
   *
   * <p>This will destroy the existing classloader, if it already exists.
   *
   * @param paths the paths to add.
   */
  public void addPaths(Collection<? extends Path> paths) {
    // Don't expand paths if this was incorrectly called with a single path, since paths themselves
    // are iterables of paths.
    for (var path : paths) {
      addPath(path);
    }
  }

  /**
   * Determine if this manager contains the given file object anywhere.
   *
   * @param fileObject the file object to look for.
   * @return {@code true} if present, {@code false} otherwise.
   */
  public boolean contains(FileObject fileObject) {
    var path = Path.of(fileObject.toUri());

    // While we could just return `Files.isRegularFile` from the start,
    // we need to make sure the path is one of the roots in the location.
    // Otherwise, we could give a false-positive.
    for (var root : roots) {
      if (path.startsWith(root)) {
        return Files.isRegularFile(path);
      }
    }

    return false;
  }

  /**
   * Get a classloader for this location manager.
   *
   * <p>This will initialize a classloader if it has not been initialized since the last path was
   * added.
   *
   * @return the class loader.
   */
  public ClassLoader getClassLoader() {
    return classLoader.access();
  }

  /**
   * Find an existing file object with the given package and relative file name.
   *
   * @param packageName  the package name.
   * @param relativeName the relative file name.
   * @return the file, or an empty optional if not found.
   */
  public Optional<FileObject> getFileForInput(String packageName, String relativeName) {
    var relativePath = packageNameToRelativePath(packageName);
    for (var root : roots) {
      var path = root.resolve(relativePath).resolve(relativeName);
      if (Files.isRegularFile(path)) {
        return Optional.of(javaFileObjectFromPath(path, relativePath));
      }
    }

    return Optional.empty();
  }

  /**
   * Get or create a file for output.
   *
   * <p>This will always use the first path that was registered.
   *
   * @param packageName  the package to create the file in.
   * @param relativeName the relative name of the file to create.
   * @return the file object for output, or an empty optional if no paths existed to place it in.
   */
  public Optional<FileObject> getFileForOutput(String packageName, String relativeName) {
    var relativePath = packageNameToRelativePath(packageName);

    return roots
        .stream()
        .findFirst()
        .map(root -> root.resolve(relativePath).resolve(relativeName))
        .map(path -> javaFileObjectFromPath(path, relativePath));
  }

  /**
   * Find an existing file object with the given class name and kind.
   *
   * @param className the class name.
   * @param kind      the kind.
   * @return the file, or an empty optional if not found.
   */
  public Optional<JavaFileObject> getJavaFileForInput(String className, Kind kind) {
    var relativePath = classNameToRelativePath(className, kind.extension);
    for (var root : roots) {
      var path = root.resolve(relativePath);
      if (Files.isRegularFile(path)) {
        return Optional.of(javaFileObjectFromPath(path, className));
      }
    }

    return Optional.empty();
  }

  /**
   * Get or create a file for output.
   *
   * <p>This will always use the first path that was registered.
   *
   * @param className the class name of the file to create.
   * @param kind      the kind of file to create.
   * @return the file object for output, or an empty optional if no paths existed to place it in.
   */
  public Optional<JavaFileObject> getJavaFileForOutput(String className, Kind kind) {
    var relativePath = classNameToRelativePath(className, kind.extension);

    return roots
        .stream()
        .findFirst()
        .map(root -> root.resolve(relativePath))
        .map(path -> javaFileObjectFromPath(path, className));
  }

  /**
   * Get the corresponding {@link Location} handle for this manager.
   *
   * @return the location.
   */
  public Location getLocation() {
    return location;
  }

  /**
   * Get a snapshot of the iterable of the paths in this location.
   *
   * @return the list of the paths that were loaded at the time the method was called, in the order
   * they are considered.
   */
  public List<? extends Path> getPaths() {
    return List.copyOf(roots);
  }

  /**
   * Get a service loader for this location.
   *
   * @param service the service type.
   * @param <S>     the service type.
   * @return the service loader.
   * @throws UnsupportedOperationException if this manager is for a module location.
   */
  public <S> ServiceLoader<S> getServiceLoader(Class<S> service) {
    if (location instanceof ModuleLocation) {
      throw new UnsupportedOperationException("Cannot load services from specific modules");
    }

    getClass().getModule().addUses(service);
    if (location.isModuleOrientedLocation()) {
      var finder = ModuleFinder.of(roots.toArray(new Path[0]));
      var bootLayer = ModuleLayer.boot();
      var config = bootLayer
          .configuration()
          .resolveAndBind(ModuleFinder.of(), finder, Collections.emptySet());
      var layer = bootLayer
          .defineModulesWithOneLoader(config, ClassLoader.getSystemClassLoader());
      return ServiceLoader.load(layer, service);
    } else {
      return ServiceLoader.load(service, classLoader.access());
    }
  }

  /**
   * Infer the binary name of the given file object.
   *
   * <p>This will attempt to find the corresponding root path for the file object, and then
   * output the relative path, converted to a class name-like string. If the file object does not
   * have a root in this manager, expect an empty optional to be returned instead.
   *
   * @param fileObject the file object to infer the binary name for.
   * @return the binary name of the object, or an empty optional if unknown.
   */
  public Optional<String> inferBinaryName(JavaFileObject fileObject) {
    var path = Path.of(fileObject.toUri());

    for (var root : roots) {
      var resolvedPath = root.resolve(path);
      if (path.startsWith(root) && Files.isRegularFile(resolvedPath)) {
        var relativePath = root.relativize(resolvedPath);
        return Optional.of(pathToObjectName(relativePath, fileObject.getKind().extension));
      }
    }

    return Optional.empty();
  }

  /**
   * Determine if the manager is empty or not.
   *
   * @return {@code true} if empty, {@code false} otherwise.
   */
  public boolean isEmpty() {
    return roots.isEmpty();
  }

  /**
   * List the {@link JavaFileObject} objects in the given package.
   *
   * @param packageName the package name.
   * @param kinds       the kinds to allow.
   * @param recurse     {@code true} to search recursively, {@code false} to only consider the given
   *                    package directly.
   * @return an iterable of file objects that were found.
   * @throws IOException if any of the paths cannot be read due to an IO error.
   */
  public Iterable<JavaFileObject> list(
      String packageName,
      Set<Kind> kinds,
      boolean recurse
  ) throws IOException {
    var relativePath = packageNameToRelativePath(packageName);
    var maxDepth = walkDepth(recurse);
    var results = new ArrayList<JavaFileObject>();

    for (var root : roots) {
      var path = root.resolve(relativePath);

      if (!Files.isDirectory(path)) {
        // Path doesn't exist, move along.
        continue;
      }

      Files
          .walk(path, maxDepth)
          .filter(hasAnyKind(kinds).and(Files::isRegularFile))
          .map(this::javaFileObjectFromPath)
          .forEach(results::add);
    }

    return results;
  }

  /**
   * Remove an existing path from the manager.
   *
   * <p>This will destroy the existing classloader, if it already exists.
   *
   * @param path the path to remove.
   */
  public void removePath(Path path) {
    classLoader.destroy();
    roots.remove(path);
  }

  /**
   * Remove one or more paths from the manager if they are present.
   *
   * <p>This will destroy the existing classloader, if it already exists.
   *
   * <p>This is a best-effort operation.
   *
   * @param paths the paths to remove.
   */
  public void removePaths(Iterable<? extends Path> paths) {
    for (var path : paths) {
      removePath(path);
    }
  }

  /**
   * {@inheritDoc}
   *
   * @return a string representation of this object.
   */
  @Override
  public String toString() {
    return "PackageOrientedPathLocationManager{"
        + "location=" + StringUtils.quoted(location.getName())
        + "}";
  }

  private ClassLoader createClassLoaderUnsafe() {
    return new PathClassLoader(roots);
  }

  private String pathToObjectName(Path path, String extension) {
    var pathString = path.toString();
    return pathString
        .substring(0, pathString.length() - extension.length())
        .replace('/', '.');
  }

  private String packageNameToRelativePath(String packageName) {
    // First arg has to be empty to be able to accept variadic arguments properly.
    return String.join("/", PACKAGE_SPLITTER.splitToArray(packageName));
  }

  private String classNameToRelativePath(String className, String extension) {
    var parts = PACKAGE_SPLITTER.splitToArray(className);
    assert parts.length > 0 : "did not expect an empty classname";
    parts[parts.length - 1] += extension;
    return String.join("/", parts);
  }

  private Predicate<Path> hasAnyKind(Iterable<Kind> kinds) {
    return path -> {
      var fileName = path.getFileName().toString();
      for (var kind : kinds) {
        if (fileName.endsWith(kind.extension)) {
          return true;
        }
      }
      return false;
    };
  }

  private int walkDepth(boolean recurse) {
    return recurse ? Integer.MAX_VALUE : 1;
  }

  private PathJavaFileObject javaFileObjectFromPath(Path path) {
    return javaFileObjectFromPath(path, path.toString());
  }

  private PathJavaFileObject javaFileObjectFromPath(Path path, String givenName) {
    // The JavaFileManager states we need to take care to keep the same representation of the
    // file name that we were provided with where possible. Thus, we need to redundantly store
    // that name as well as the path.
    return new PathJavaFileObject(location, path, givenName);
  }
}
