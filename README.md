# Crema repro: Class.forName not dispatchable from runtime-loaded bytecode

`Class.forName` is internally substituted by GraalVM native-image. The
substitution is inlined at each call site, so the original method is never
compiled as a standalone entry point. When Crema's interpreter encounters
`invokestatic java.lang.Class.forName(String)` in runtime-loaded bytecode,
there is no compiled code to dispatch to.

## Build

Requires a GraalVM EA build with RuntimeClassLoading support.

```sh
JAVA_HOME=/path/to/graalvm-ea bash build.sh
```

## Run

```sh
./main
```

Expected output: `Class: java.util.ArrayList`

Actual output:

```
Fatal error: Trying to dispatch to compiled code for AOT method
InterpreterResolvedJavaMethod<holder=Ljava/lang/Class; name=forName
descriptor=(Ljava/lang/String;)Ljava/lang/Class;> but it was not compiled
because it was not seen as reachable by analysis
```

## What doesn't help

- `java.lang` is already preserved via `-H:Preserve=package=java.lang`
- `reflect-config.json` registers `Class.forName` for reflection (included in build)

Neither makes the original method available as a dispatch target for Crema.
