# java-debug launcher (standalone server)

The java-debug server (`microsoft/java-debug`) is normally a plugin that runs
*inside* the Eclipse JDT Language Server. This folder holds a standalone
entrypoint + provider shims so the app can run it as a plain subprocess over
stdio — no jdtls required.

## Layout

```
tool/java-debug-launcher/
├── ServerLauncher.java                 # main(): registers providers, starts ProtocolServer over stdio
├── SimpleVirtualMachineManagerProvider # plain JDI attach (Bootstrap.virtualMachineManager)
├── SimpleSourceLookUpProvider          # source file -> class name mapping for breakpoints
├── SimpleEvaluationProvider            # JDI expression evaluator + invokeMethod (collections, watch)
├── SimpleEvaluator.java                # lightweight expression parser/evaluator over JDI
├── SimpleHotCodeReplaceProvider        # stub (no-op)
└── SimpleCompletionsProvider           # stub (empty)
```

The assembled runtime lands in `build/java-debug/` (gitignored — never committed):

```
build/java-debug/
├── com.microsoft.java.debug.core-<version>.jar   # built from microsoft/java-debug
├── server-launcher.jar                           # compiled from this folder
└── lib/*.jar                                     # runtime deps (via maven build-classpath)
```

## Prerequisites

- JDK 17+ (`java`/`javac`, or `JAVA_HOME`)
- a clone of `microsoft/java-debug` at `../java-debug` (builds with its own `./mvnw`, no Maven install needed)
- first build downloads Maven + dependencies from Maven Central

## Scenario 1: rebuild the launcher (common case)

After editing any `.java` here:

```sh
cd <repo>
rm -rf /tmp/launcher-out && mkdir -p /tmp/launcher-out
CP="build/java-debug/com.microsoft.java.debug.core-0.53.2.jar:build/java-debug/lib/*"
javac -cp "$CP" -d /tmp/launcher-out tool/java-debug-launcher/*.java
jar cf build/java-debug/server-launcher.jar -C /tmp/launcher-out .
```

That is all that is needed for launcher/provider changes.

## Scenario 2: rebuild java-debug itself / bump version

```sh
cd ../java-debug
./mvnw -DskipTests clean install
./mvnw -q -pl com.microsoft.java.debug.core dependency:build-classpath -Dmdep.outputFile=/tmp/java-debug-cp.txt

cd <repo>
cp ../java-debug/com.microsoft.java.debug.core/target/com.microsoft.java.debug.core-*.jar build/java-debug/
rm -f build/java-debug/lib/*.jar
tr ':' '\n' < /tmp/java-debug-cp.txt | while read -r jar; do cp "$jar" build/java-debug/lib/; done
```

Then recompile the launcher (Scenario 1) against the new core jar — the API
may have changed, and the launcher is pinned to the built version.

## Verify

```sh
cd <repo>
JAVA_DEBUG_SERVER=build/java-debug dart run tool/repro_start.dart
```

`tool/repro_start.dart` drives the full pipeline headlessly (compile -> attach
-> breakpoint -> evaluate) and prints every step.

## How the app finds it

`JavaAdapter._locateServerJar` checks, in order:

1. `JAVA_DEBUG_SERVER` env var (file or directory)
2. the VS Code Java extension's `server/` folder under `~/.vscode/extensions/`
3. `<projectRoot>/build/java-debug/`

The app's default entrypoint is `tool.java_debug_launcher.ServerLauncher`
(classpath = core jar + sibling jars + `lib/*.jar`).

## Notes

- java-debug 0.53.2 dropped the standalone `adapter.Server` main class; the
  modern architecture runs the server inside jdtls. This launcher is the
  standalone substitute.
- Collection rendering works (logical size via JDI `invokeMethod`). Arbitrary
  expression evaluation works for the common subset (locals, fields, method
  calls, arithmetic, comparisons, `&&`/`||`, string concat, array `.length`).
  A full compiler-backed evaluator would require the jdtls integration
  (LSP `java.startDebugSession` -> TCP port), which is the future roadmap.

# Flow
lib/src/session/dap_session.dart:53-75
await _launcher.compile(config.source, ...);                    // javac
_targetProcess = await _launcher.launchTarget(...);             // debuggee JVM (JDWP :5005)
final (transport, serverProcess) = await _launcher.spawnDebugServer(); // ← OUR launcher JVM
final client = _clientFactory(transport);                       // DapClient over its stdio
final debugger = DapDebugger()..connect(client);
await debugger.initialize(); ... attach(...) ... await initialized;
