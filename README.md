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
- Reset `doInit()` guard at runtime for `in-ns`/`refer` setup, but skip
  `clojure.core.server` require (already loaded at build time, avoids
  re-loading spec etc.)

**`Var.java`**:
- During native-image build-time class init (`imagecode=buildtime`), `set!` falls
  back to `bindRoot()` instead of throwing. Fixes "Can't change/establish root
  binding of: *warn-on-reflection* with set" for all namespaces.

**`PersistentTreeMap.java`**:
- Use `Util::compare` directly instead of `RT.DEFAULT_COMPARATOR` in no-arg
  constructor. Breaks circular class init deadlock: `RT` ↔ `PersistentTreeMap`.

**`MultiFn.java`**:
- Use `Var.intern()` directly instead of `RT.var()` for static fields (`assoc`,
  `dissoc`, `isa`, `parents`). Breaks circular class init: `RT` ↔ `MultiFn`.

**`Compiler.java`**:
- Forces `freshLoader=true` in `eval`

**`core.clj`**:
- Large diff (mostly reformatting/reordering)

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

### Circular class init deadlocks

Native-image initializes classes in parallel, which exposes circular dependencies
between `clojure.lang` classes and `RT`:

1. **`RT` ↔ `PersistentTreeMap`**: `RT.<clinit>` loads core → needs
   `PersistentTreeSet` → needs `PersistentTreeMap.<clinit>` → calls
   `this(RT.DEFAULT_COMPARATOR)` → waits on `RT`.
   **Fix**: Use `Util::compare` directly.

2. **`RT` ↔ `MultiFn`**: `MultiFn.<clinit>` calls `RT.var(...)` → needs
   `RT.<clinit>` → loads core → uses `MultiFn` → waits on `MultiFn`.
   **Fix**: Use `Var.intern()` directly instead of `RT.var()`.

3. **`RT` ↔ `__init` classes**: Native-image eagerly initializes `__init` classes
   (e.g. `clojure.core.protocols__init`, `clojure.edn__init`) on parallel threads,
   all waiting on `RT`, while `RT` waits on them to complete.
   **Fix**: Force single-threaded class init with
   `-J-Djava.util.concurrent.ForkJoinPool.common.parallelism=1` (build-time only,
   does not affect the resulting binary). Slower builds but deterministic.

### Preserve packages (for Crema runtime)

- `clojure.lang` — Clojure's `creator` static field (functional interface support)
- `java.lang.invoke` — method handle infrastructure (`DelegatingMethodHandle$Holder`)
- `java.util.regex` — Clojure uses regexes heavily in core
- `java.lang`, `java.util`, `java.io`, `java.util.concurrent` — standard library

### Known issues

1. **Crema method handle bug** (seen with stock Clojure 1.12.3, not the fork):
   `ClassCastException: Integer cannot be cast to Boolean` in
   `MethodHandleUtils.intUnbox` when `Reflector.canAccess()` calls
   `Method.canAccess(Object)` through a method handle. Crema bug to report.

2. **More circular deps may exist** — other `clojure.lang` classes may have
   static fields referencing `RT`. If new cycles surface, the pattern is the same:
   replace `RT.var()` with `Var.intern()`, replace `RT.DEFAULT_COMPARATOR` with
   direct alternatives, etc.

3. **Binary requires `JAVA_HOME`** — Crema loads classes at runtime from the
   JDK's `lib/modules` (JRT filesystem). The binary is not fully standalone;
   it needs a GraalVM installation available. The `SystemImage.findHome()`
   substitution reads `JAVA_HOME` env var or `java.home` system property.
