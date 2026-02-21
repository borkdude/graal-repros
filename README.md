# Graal repros

github.com/borkdude/graal-repros branch clojure-crema

## Goal

Use Crema (GraalVM's JIT-in-native-image / RuntimeClassLoading) with Clojure to
enable runtime `eval` in a native binary.

## Status

**Working!** The native binary evaluates arbitrary Clojure expressions at runtime:

```
$ JAVA_HOME=$HOME/Downloads/graalvm-25.1.0-dev+8.1/Contents/Home ./cream '(+ 1 2 3)'
6
```

## GraalVM EA build

Crema requires a GraalVM EA build with RuntimeClassLoading support. The standard
GraalVM from sdkman does not include this.

Download and extract:

```sh
curl -L -o ~/Downloads/graalvm-jdk-25e1-25.0.1-ea.14_macos-aarch64_bin.tar.gz \
  "https://github.com/graalvm/oracle-graalvm-ea-builds/releases/download/jdk-25e1-25.0.1-ea.14/graalvm-jdk-25e1-25.0.1-ea.14_macos-aarch64_bin.tar.gz"
tar -xzf ~/Downloads/graalvm-jdk-25e1-25.0.1-ea.14_macos-aarch64_bin.tar.gz -C ~/Downloads
```

This extracts to `~/Downloads/graalvm-25.1.0-dev+8.1`.

## Custom Clojure fork

Uses `~/dev/clojure` branch `crema` (`1.13.0-master-SNAPSHOT`). Install with:

```sh
cd ~/dev/clojure && git checkout crema && mvn install -Dmaven.test.skip=true
```

### Fork changes

**`RT.java`**:
- Skip loading `clojure/core` at native-image runtime (already loaded at build
  time) using `org.graalvm.nativeimage.imagecode` property check
- Skip `doInit()` entirely at native-image runtime (user ns, refer, and server
  were all set up at build time and captured in the image)
- Wrap `clojure.core.server` loading in `!nativeImageRuntime` guard (avoids
  re-loading spec etc. at runtime)

**`Var.java`**:
- During native-image build-time class init (`imagecode=buildtime`), `set!` falls
  back to `bindRoot()` instead of throwing. Fixes "Can't change/establish root
  binding of: *warn-on-reflection* with set" for all namespaces.

## Building

```sh
GRAALVM_HOME=$HOME/Downloads/graalvm-25.1.0-dev+8.1/Contents/Home bb bb/build_native.clj
```

## Running

The binary needs access to the JRT filesystem for runtime class loading.
Set `JAVA_HOME` or pass `-Djava.home=...`:

```sh
JAVA_HOME=$HOME/Downloads/graalvm-25.1.0-dev+8.1/Contents/Home ./cream '(+ 1 2 3)'
```

Without arguments, evaluates `(assoc {} :foo :bar)` as default.

### Loading libraries at runtime (`-cp`)

The `-cp` flag adds JARs/directories to the classpath at runtime, enabling
`require` of libraries not bundled in the native image:

```sh
./cream -cp ~/.m2/repository/org/clojure/data.csv/1.1.0/data.csv-1.1.0.jar \
  '(do (require (quote clojure.data.csv)) (let [sw (java.io.StringWriter.)] ((resolve (quote clojure.data.csv/write-csv)) sw [["a" "b"] ["1" "2"]]) (str sw)))'
;; => "a,b\n1,2\n"
```

Uses `JarClassLoader` (`src-java/my/JarClassLoader.java`), a custom classloader
extending `DynamicClassLoader` that reads JARs via `java.util.jar.JarFile`
directly. This works around `URLClassLoader.findResource()` not functioning in
GraalVM native images with Crema/RuntimeClassLoading.

Libraries with transitive Clojure standard library dependencies (e.g.,
`data.json` → `pprint` → `clojure.walk`) work because all standard Clojure
namespaces are required at build time in `repro.clj`. This means `require` at
runtime is a no-op for these — no reachability issues.

```sh
./cream -cp "$(clojure -Spath -Sdeps '{:deps {org.clojure/data.json {:mvn/version "RELEASE"}}}')" \
  '(do (require (quote [clojure.data.json :as json])) (json/write-str {:a 1}))'
;; => "{\"a\":1}"
```

### Performance

```
$ time ./cream '(+ 1 2 3)'
6
  0.02s total

$ time ./cream -cp <data.json jar> '(do (require ...) (json/write-str {:a 1}))'
"{\"a\":1}"
  0.07s total
```

## Key findings

### Substitutions (`src-java/Target_jdk_internal_misc_VM.java`)

- `jdk.internal.misc.VM.initialize()` — replaced with no-op (still needed)
- `jdk.internal.jrtfs.SystemImage.findHome()` — returns `System.getProperty("java.home")`
  to workaround `getProtectionDomain().getCodeSource()` issue for boot classes
- `VM.getRuntimeArguments()` substitution was **removed** — the 25e1 EA build
  already provides it internally (duplicate causes "conflicts with previously
  registered" error)

### Class initialization

- `--initialize-at-build-time=clojure` — needed so Clojure core is AOT'd at
  build time. The `Var.set()` fork fix handles the `*warn-on-reflection*` issue.
- `jdk.internal.jrtfs.SystemImage` must be in `--initialize-at-run-time`,
  otherwise the analysis phase deadlocks.

### Deterministic class initialization (`ClojureFeature`)

Native-image with `--initialize-at-build-time=clojure` eagerly initializes ALL
`clojure.*` classes in parallel during analysis. This causes circular class init
deadlocks because compiled Clojure classes (fn, deftype, `__init`) reference `RT`
in their `<clinit>`, while `RT.<clinit>` loads core which needs those classes.

**Solution**: A GraalVM `Feature` (`src-java/ClojureFeature.java`) forces
`RT.<clinit>` to complete in `beforeAnalysis()`, which runs on a single thread
before the parallel analysis phase. This sequentially initializes all core
namespaces, fn classes, and deftype classes. When analysis later discovers these
classes, they're already initialized — no deadlocks.

### Why the Feature is sufficient

Earlier approaches required modifying `PersistentTreeMap`, `MultiFn`, `Compiler`,
and `__init` class generation to break circular class init dependencies (e.g.,
`RT` ↔ `PersistentTreeMap`, `RT` ↔ `MultiFn`, `RT` ↔ `__init` classes,
`RT` ↔ `Compiler`). These were all **reverted** because the Feature makes them
unnecessary: since `RT.<clinit>` runs to completion on a single thread before
parallel analysis, Java's reentrant class initialization allows same-thread
access to partially-initialized classes without deadlock.

### Preserve packages (for Crema runtime)

- `clojure.lang` — Clojure's `creator` static field (functional interface support)
- `java.lang.invoke` — method handle infrastructure (`DelegatingMethodHandle$Holder`)
- `java.util.regex` — Clojure uses regexes heavily in core
- `java.lang`, `java.util`, `java.io`, `java.util.concurrent` — standard library
- `java.lang.reflect` — needed for proxy/reflection (e.g., pprint)
- `java.net` — needed for `java.net.URL` constructor
- `java.util.jar`, `java.util.zip` — needed for JarClassLoader
- `java.time`, `java.time.format` — needed for `DateTimeFormatter/ISO_INSTANT`
  (used by `clojure.instant`)

### URL protocols

`--enable-url-protocols=http,https,jar,unix` — the `jar:` protocol is required
for `JarClassLoader.getResource()` to construct `jar:file:...!/...` URLs in
native image. Without it, `new URL("jar:...")` throws `MalformedURLException`.

### JarClassLoader (`src-java/my/JarClassLoader.java`)

Custom classloader extending `DynamicClassLoader` for use in native images:
- Indexes all JAR entries at construction for O(1) resource lookup
- `getResourceAsStream()` — reads from JARs via `JarFile.getInputStream()`
- `getResource()` — returns `jar:file:` URLs
- `findClass()` — reads `.class` bytes from JARs and calls `defineClass()`
- Supports both JAR files and directories on the classpath
- Falls back to parent classloader for resources not found locally

### Known issues

1. **Crema method handle bug** (seen with stock Clojure 1.12.3, not the fork):
   `ClassCastException: Integer cannot be cast to Boolean` in
   `MethodHandleUtils.intUnbox` when `Reflector.canAccess()` calls
   `Method.canAccess(Object)` through a method handle. Crema bug to report.

2. **Binary requires `JAVA_HOME`** — Crema loads classes at runtime from the
   JDK's `lib/modules` (JRT filesystem). The binary is not fully standalone;
   it needs a GraalVM installation available. The `SystemImage.findHome()`
   substitution reads `JAVA_HOME` env var or `java.home` system property.

### Build-time namespace loading

Runtime-loaded libraries that transitively depend on Clojure standard library
namespaces (e.g., `data.json` → `pprint` → `clojure.walk`) would fail because
core fns like `use` aren't seen as reachable by native-image analysis.

**Solution**: Require all standard Clojure namespaces at build time in
`repro.clj` (`clojure.pprint`, `clojure.walk`, `clojure.set`, `clojure.xml`,
etc.). This means runtime `require` calls for these are no-ops — they're already
loaded in the image.
