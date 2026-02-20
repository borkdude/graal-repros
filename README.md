# Graal repros

github.com/borkdude/graal-repros branch clojure-crema

## Goal

Use Crema (GraalVM's JIT-in-native-image / RuntimeClassLoading) with Clojure to
enable runtime `eval` in a native binary.

The repro program (`src/my/repro.clj`) evaluates `(assoc {} :foo :bar)` at
runtime via `clojure.lang.Compiler/eval`.

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

### Substitutions

The file `src-java/Target_jdk_internal_misc_VM.java` provides two substitutions:

- `jdk.internal.misc.VM.initialize()` — replaced with no-op (still needed)
- `jdk.internal.jrtfs.SystemImage.findHome()` — returns `System.getProperty("java.home")` to workaround `getProtectionDomain().getCodeSource()` issue for boot classes

Note: the `VM.getRuntimeArguments()` substitution was **removed** because the
25e1 EA build already provides it internally. Having both causes a
"conflicts with previously registered" error at build time.

### Class initialization

- `clojure` must NOT be in `--initialize-at-build-time`. Several Clojure
  namespaces (e.g. `clojure.core.server`, `clojure.spec.alpha`) use `set!` on
  `*warn-on-reflection*` during load, which fails at build time with
  "Can't change/establish root binding". Removing `clojure` from build-time init
  lets Crema load Clojure from source at runtime (the intended behavior).
- `jdk.internal.jrtfs.SystemImage` must be in `--initialize-at-run-time`,
  otherwise the analysis phase hangs (deadlock with 0% CPU).

### Preserve packages

Crema needs certain packages preserved for runtime reflection and method handles:

- `clojure.lang` — Clojure's `creator` static field (functional interface support)
- `java.lang.invoke` — method handle infrastructure (`DelegatingMethodHandle$Holder`)
- `java.lang`, `java.util`, `java.io`, `java.util.concurrent` — standard library

### Current blocker: Crema bug

The binary builds successfully but crashes at runtime with:

```
ClassCastException: java.lang.Integer cannot be cast to java.lang.Boolean
    at com.oracle.svm.core.invoke.MethodHandleUtils.intUnbox
```

This occurs in Crema's method handle dispatch when `Reflector.canAccess()` calls
`Method.canAccess(Object)` (returns `boolean`) through a method handle. The
Crema interpreter's `intUnbox` incorrectly handles the boolean return type.

This is a Crema bug to report to the GraalVM team.
