package tool.java_debug_launcher;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.microsoft.java.debug.core.DebugException;
import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.protocol.Types.SourceBreakpoint;

public class SimpleSourceLookUpProvider implements ISourceLookUpProvider {
    @Override
    public boolean supportsRealtimeBreakpointVerification() {
        return false;
    }

    @Override
    public String[] getFullyQualifiedName(String uri, int[] lines, int[] columns) throws DebugException {
        return new String[] { deriveClassName(uri, getSourceContents(uri)) };
    }

    @Override
    public JavaBreakpointLocation[] getBreakpointLocations(String sourceUri, SourceBreakpoint[] sourceBreakpoints)
            throws DebugException {
        String className = deriveClassName(sourceUri, getSourceContents(sourceUri));
        JavaBreakpointLocation[] locations = new JavaBreakpointLocation[sourceBreakpoints.length];
        for (int i = 0; i < sourceBreakpoints.length; i++) {
            locations[i] = new JavaBreakpointLocation(sourceBreakpoints[i].line, sourceBreakpoints[i].column);
            locations[i].setClassName(className);
        }
        return locations;
    }

    @Override
    public String getSourceFileURI(String fullyQualifiedName, String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return fullyQualifiedName + ".java";
        }
        return sourcePath + "/" + fullyQualifiedName.replace('.', '/') + ".java";
    }

    @Override
    public String getSourceContents(String uri) {
        try {
            return new String(Files.readAllBytes(toPath(uri)));
        } catch (IOException | InvalidPathException e) {
            return "";
        }
    }

    /**
     * DAP clients may send source locations as plain filesystem paths or as
     * {@code file:} URIs; accept both so the package declaration can be read.
     */
    private static Path toPath(String uri) {
        if (uri != null && uri.startsWith("file:")) {
            return Paths.get(URI.create(uri));
        }
        return Paths.get(uri);
    }

    @Override
    public List<MethodInvocation> findMethodInvocations(String uri, int line) {
        return new ArrayList<>();
    }

    static String deriveClassName(String uri, String sourceContents) {
        String simpleName = simpleClassName(uri);
        String packageName = parsePackage(sourceContents);
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private static String simpleClassName(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        int slash = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('\\'));
        String name = slash >= 0 ? uri.substring(slash + 1) : uri;
        if (name.endsWith(".java")) {
            name = name.substring(0, name.length() - ".java".length());
        }
        return name;
    }

    private static String parsePackage(String sourceContents) {
        if (sourceContents == null || sourceContents.isEmpty()) {
            return "";
        }
        for (String line : sourceContents.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                return trimmed.substring("package ".length())
                        .replaceFirst(";.*$", "")
                        .trim();
            }
        }
        return "";
    }
}
