package tool.java_debug_launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * End-to-end test for the standalone launcher: spawns {@link ServerLauncher} as a
 * subprocess and drives a full DAP session over stdio (initialize -> launch ->
 * setBreakpoints -> configurationDone -> stopped).
 */
class ServerLauncherIT {

    private static final String FIXTURE_RESOURCE = "/fixtures/com/example/HelloWorld.java";
    private static final String MAIN_CLASS = "com.example.HelloWorld";
    private static final int BREAKPOINT_LINE = 6;

    @Test
    void launchSessionHitsBreakpointInPackagedClass(@TempDir Path tempDir) throws Exception {
        Path fixtureSource = tempDir.resolve("src/com/example/HelloWorld.java");
        Path classesDir = compileFixture(fixtureSource);

        try (DapClient client = DapClient.spawn(launcherCommand())) {
            // 1. initialize -- must succeed and report capabilities.
            JsonObject initResponse = client.request("initialize", initializeArguments());
            assertTrue(initResponse.get("success").getAsBoolean(), "initialize failed: " + initResponse);
            JsonObject capabilities = initResponse.getAsJsonObject("body");
            assertTrue(capabilities.has("supportsConfigurationDoneRequest"),
                    "initialize response is missing capabilities: " + initResponse);

            // 2. launch -- returns once the debuggee JVM is connected and suspended.
            JsonObject launchResponse = client.request("launch", launchArguments(classesDir, tempDir));
            assertTrue(launchResponse.get("success").getAsBoolean(), "launch failed: " + launchResponse);

            // The adapter emits 'initialized' when it is ready for configuration requests.
            client.awaitEvent("initialized");

            // 3. setBreakpoints on a class that lives in a package (exercises FQN resolution).
            JsonObject breakpointResponse = client.request("setBreakpoints", breakpointArguments(fixtureSource));
            assertTrue(breakpointResponse.get("success").getAsBoolean(),
                    "setBreakpoints failed: " + breakpointResponse);
            JsonArray breakpoints = breakpointResponse.getAsJsonObject("body").getAsJsonArray("breakpoints");
            assertEquals(1, breakpoints.size(), "expected one breakpoint: " + breakpointResponse);
            JsonObject breakpoint = breakpoints.get(0).getAsJsonObject();
            // The class is not loaded yet, so java-debug reports verified=false here and
            // resolves it later via a 'breakpoint' event. What must hold now is that the
            // adapter resolved the request to the line we asked for (FQN lookup worked).
            assertEquals(BREAKPOINT_LINE, breakpoint.get("line").getAsInt(),
                    "breakpoint did not resolve to the requested line: " + breakpointResponse);

            // 4. configurationDone -> debuggee resumes -> hits the breakpoint.
            client.request("configurationDone", new JsonObject());
            JsonObject stopped = client.awaitEvent("stopped");
            assertEquals("breakpoint", stopped.getAsJsonObject("body").get("reason").getAsString(),
                    "debuggee did not stop on the breakpoint: " + stopped);

            // 5. clean shutdown.
            client.request("disconnect", disconnectArguments());
        }
    }

    private static Path compileFixture(Path fixtureSource) throws IOException {
        Files.createDirectories(fixtureSource.getParent());
        try (InputStream in = ServerLauncherIT.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            assertNotNull(in, "fixture resource not found on classpath: " + FIXTURE_RESOURCE);
            Files.copy(in, fixtureSource);
        }

        Path classesDir = fixtureSource.getParent().getParent().getParent().getParent().resolve("classes");
        Files.createDirectories(classesDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "no system Java compiler available; run the build on a JDK");
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(List.of(fixtureSource.toFile()));
            List<String> options = List.of("-d", classesDir.toString());
            boolean compiled = compiler.getTask(null, fileManager, null, options, null, units).call();
            assertTrue(compiled, "failed to compile the fixture: " + fixtureSource);
        }
        return classesDir;
    }

    private static List<String> launcherCommand() {
        String launcherDir = System.getProperty("launcher.dir", "build/java-debug");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = launcherDir + "/*" + File.pathSeparator + launcherDir + "/lib/*";

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.add("-cp");
        command.add(classpath);
        command.add("tool.java_debug_launcher.ServerLauncher");
        return command;
    }

    private static JsonObject initializeArguments() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("clientID", "java-debug-launcher-it");
        arguments.addProperty("adapterID", "java");
        arguments.addProperty("pathFormat", "path");
        arguments.addProperty("linesStartAt1", true);
        arguments.addProperty("columnsStartAt1", true);
        return arguments;
    }

    private static JsonObject launchArguments(Path classesDir, Path cwd) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("mainClass", MAIN_CLASS);
        arguments.add("classPaths", arrayOf(classesDir.toString()));
        arguments.addProperty("cwd", cwd.toString());
        // internalConsole avoids a runInTerminal round-trip the test client does not serve.
        arguments.addProperty("console", "internalConsole");
        return arguments;
    }

    private static JsonObject breakpointArguments(Path sourceFile) {
        JsonObject source = new JsonObject();
        source.addProperty("name", sourceFile.getFileName().toString());
        source.addProperty("path", sourceFile.toString());

        JsonObject breakpoint = new JsonObject();
        breakpoint.addProperty("line", BREAKPOINT_LINE);

        JsonArray breakpoints = new JsonArray();
        breakpoints.add(breakpoint);

        JsonObject arguments = new JsonObject();
        arguments.add("source", source);
        arguments.add("breakpoints", breakpoints);
        return arguments;
    }

    private static JsonObject disconnectArguments() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("terminateDebuggee", true);
        return arguments;
    }

    private static JsonArray arrayOf(String value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }
}
