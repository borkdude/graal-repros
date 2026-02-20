# Graal repros

github.com/borkdude/graal-repros branch clojure-crema

## Goal

Use Crema (GraalVM's JIT-in-native-image / RuntimeClassLoading) with Clojure to
enable runtime `eval` in a native binary.

The repro program (`src/my/repro.clj`) evaluates `(assoc {} :foo :bar)` at
runtime via `clojure.lang.Compiler/eval`.

## Status

We got `{:foo :bar}` output once! But the build hangs intermittently at
"[2/8] Performing analysis..." with 0% CPU. The `--initialize-at-build-time=clojure`
blanket approach may cause non-deterministic class initialization ordering issues.

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
- Skip loading `clojure/core` at native-image runtime (already loaded at build time)
  using `org.graalvm.nativeimage.imagecode` property check
- Reset `doInit()` guard at runtime so `in-ns`/`refer`/socket servers get set up

**`Var.java`**:
- During native-image build-time class init (`imagecode=buildtime`), `set!` falls
  back to `bindRoot()` instead of throwing. Fixes "Can't change/establish root
  binding of: *warn-on-reflection* with set" for all namespaces that use
  `(set! *warn-on-reflection* true)`.

**`Compiler.java`**:
- Debug printlns removed (were previously in `eval` methods)

**`core.clj`**:
- Large diff (mostly reformatting/reordering)

## Building

```sh
GRAALVM_HOME=$HOME/Downloads/graalvm-25.1.0-dev+8.1/Contents/Home bb bb/build_native.clj
```

## Running

The binary needs access to the JRT filesystem for runtime class loading:

```sh
./cream -Djava.home=$HOME/Downloads/graalvm-25.1.0-dev+8.1/Contents/Home
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
  otherwise the analysis phase deadlocks (0% CPU).
- **Known issue**: `--initialize-at-build-time=clojure` can cause intermittent
  hangs during analysis, likely due to non-deterministic class initialization
  ordering. When it works, the binary runs correctly.

### Preserve packages (for Crema runtime)

- `clojure.lang` — Clojure's `creator` static field (functional interface support)
- `java.lang.invoke` — method handle infrastructure (`DelegatingMethodHandle$Holder`)
- `java.util.regex` — Clojure uses regexes heavily in core
- `java.lang`, `java.util`, `java.io`, `java.util.concurrent` — standard library

### Known issues

1. **Intermittent analysis hangs** — build sometimes hangs at "[2/8] Performing
   analysis..." with 0% CPU. Likely a class initialization ordering issue with
   the blanket `--initialize-at-build-time=clojure`.

2. **Crema method handle bug** (seen with stock Clojure 1.12.3, not the fork):
   `ClassCastException: Integer cannot be cast to Boolean` in
   `MethodHandleUtils.intUnbox` when `Reflector.canAccess()` calls
   `Method.canAccess(Object)` through a method handle. This is a Crema bug.

3. **`read-string` causes analysis hang** — changing the repro to accept
   command-line args via `read-string` caused the analysis to hang. Needs
   investigation.
