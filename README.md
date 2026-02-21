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

**`Compiler.java`**:
- `StaticMethodExpr.emit()` redirects `Class.forName` calls to `RT.classForName`.
  See [Class.forName and GraalVM substitutions](#classforname-and-graalvm-substitutions)
  for the full explanation.
- `FISupport.maybeFIMethod()` catches `UnsupportedOperationException` from
  `Class.getRawAnnotations()` for Crema runtime-loaded classes.

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

### Library test results

| Library | Status | Notes |
|---------|--------|-------|
| data.csv | Works | Pure Clojure, no transitive deps |
| data.json | Works | Transitive deps (pprint, walk) loaded at build time |
| medley | Works | Pure Clojure utility library |
| camel-snake-kebab | Works | Pure Clojure |
| hiccup | Works | Pure Clojure HTML generation |
| tools.reader | Works | Fixed by preserving `java.math` package |
| prismatic/schema | Works | Fixed by preserving `java.util.concurrent.atomic` |
| tick | Works | Fixed by preserving `java.time.temporal`, `java.time.zone` |
| clj-commons/fs | Works | File system utilities |
| math.combinatorics | Works | Pure Clojure |
| flatland/useful | Works | Pure Clojure |
| specter | Works | Fixed by pre-loading `clojure.core.reducers` at build time |
| malli | Works | Validation/schema library |
| meander | Works | Fixed by `Class.forName` → `RT.classForName` compiler redirect |
| selmer | Works | Fixed by `Class.forName` → `RT.classForName` compiler redirect |
| data.xml | Fails | `javax.xml.stream` package not preserved for Crema |
| core.async | Fails | `#=` reader macro / constructor dispatch for `clojure.core$apply` |
| deep-diff2 | Fails | Protocol method resolution (`equality-partition`) |
| http-kit | Fails | Enum `values()` NPE in Crema interpreter |
| clj-yaml | Fails | Enum support (`EnumMap` NPE in Crema) |
| cheshire | Fails | Jackson enum `values()` NPE in Crema |

**Pattern**: Pure Clojure libraries work. Libraries using Java interop work
when the relevant packages are preserved. Libraries using enums hit Crema bugs.
Libraries using `Class.forName` fail due to GraalVM substitution inlining (see
[Class.forName and GraalVM substitutions](#classforname-and-graalvm-substitutions));
the Clojure fork compiler workaround should fix these for Clojure-emitted code.

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

Packages preserved via `-H:Preserve=package=X` in `build_native.clj`, based on
babashka's `impl/classes.clj` coverage:

- `clojure.lang` — Clojure's `creator` static field (functional interface support)
- `java.lang`, `java.lang.invoke`, `java.lang.ref`, `java.lang.reflect` — core
- `java.io`, `java.math`, `java.net`, `java.net.http` — I/O, math, networking
- `java.nio`, `java.nio.channels`, `java.nio.charset`, `java.nio.file`,
  `java.nio.file.attribute` — NIO
- `java.security`, `java.security.cert`, `java.security.spec` — security
- `java.sql` — JDBC
- `java.text` — `SimpleDateFormat` constructor (tools.reader, etc.)
- `java.time`, `java.time.chrono`, `java.time.format`, `java.time.temporal`,
  `java.time.zone` — date/time
- `java.util`, `java.util.concurrent`, `java.util.concurrent.atomic`,
  `java.util.concurrent.locks`, `java.util.function`, `java.util.jar`,
  `java.util.regex`, `java.util.stream`, `java.util.zip` — collections, concurrency
- `javax.crypto`, `javax.crypto.spec`, `javax.net.ssl` — crypto/SSL
- `javax.xml.namespace` — XML

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

2. **`getRawAnnotations` not implemented** — `Class.getRawAnnotations()` throws
   `UnsupportedOperationException` for runtime-loaded classes in Crema. Affects
   Clojure 1.13's `@FunctionalInterface` detection in the compiler. **Workaround**:
   the Clojure fork catches `UnsupportedOperationException` in
   `Compiler$FISupport.maybeFIMethod()` and treats it as "not a functional
   interface".

3. **Enum support broken** — `enum.values()` and `EnumMap` crash with NPE in
   `InterpreterResolvedObjectType.getDeclaredMethodsList()`. Affects libraries
   using Java enums (http-kit's `HttpMethod`, SnakeYaml's constructors).

4. **`Class.forName` not dispatchable by Crema** — See
   [Class.forName and GraalVM substitutions](#classforname-and-graalvm-substitutions).

5. **Binary requires `JAVA_HOME`** — Crema loads classes at runtime from the
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

### `clojure.reflect.java__init` initialization ordering

`clojure.reflect.clj` loads `reflect/java` via `(load "reflect/java")` from
source, so the `clojure.reflect.java__init` class is never class-initialized
during normal loading. When native-image analysis discovers it later (e.g., via
reflection config), it tries to initialize it and fails because the
`TypeReference` protocol from `clojure.reflect` isn't visible yet.

**Solution**: `ClojureFeature.beforeAnalysis()` forces initialization in the
correct order: first `clojure.lang.RT`, then `my.repro__init` (which loads all
standard namespaces including `clojure.reflect`), then
`clojure.reflect.java__init`.

### Reflection config (`bb/gen_reflect_config.clj`)

A babashka script generates `reflect-config.json` with ~470 classes based on
babashka's `impl/classes.clj` coverage. All entries have `allPublicMethods`,
`allPublicConstructors`, and `allPublicFields` set to true. Run with:

```sh
bb bb/gen_reflect_config.clj
```

### Class.forName and GraalVM substitutions

`Class.forName(String)` and `Class.forName(String, boolean, ClassLoader)` are
both internally substituted by GraalVM native-image. The substitution bodies are
**inlined** at each call site during compilation — the original methods are never
compiled as standalone entry points. When Crema's interpreter encounters
`invokestatic java.lang.Class.forName(String)` in runtime-loaded bytecode, it
looks up the AOT method table, finds the method metadata (preserved via
`-H:Preserve=package=java.lang`), but there is no compiled code to dispatch to.

Key observations:
- Adding calls to `Class.forName` in application code does NOT help — the
  analysis intrinsifies/inlines the substitution at each call site
- Adding `Class.forName` to `reflect-config.json` does NOT help — reflection
  registration is separate from method compilation
- Creating a custom `@Substitute` for `Class.forName` conflicts with GraalVM's
  internal substitution ("conflicts with previously registered")
- `RT.classForName(String)` **does work** — it's a non-substituted Clojure
  method that internally calls the 3-arg `Class.forName` (inlined by the
  substitution at compile time)

**Workaround**: The Clojure fork's `Compiler.java` redirects
`(Class/forName ...)` interop calls to emit `invokestatic RT.classForName`
instead of `invokestatic Class.forName`. This fixes all Clojure-emitted code
(eval'd expressions and Clojure libraries). Java `.class` files that directly
call `Class.forName` would still fail — this is a Crema limitation to report.
