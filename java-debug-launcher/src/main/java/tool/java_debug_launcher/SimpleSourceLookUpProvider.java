package tool.java_debug_launcher;

import java.io.IOException;
import java.nio.file.Files;
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
        return new String[] { deriveClassName(uri) };
    }

    @Override
    public JavaBreakpointLocation[] getBreakpointLocations(String sourceUri, SourceBreakpoint[] sourceBreakpoints)
            throws DebugException {
        String className = deriveClassName(sourceUri);
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
            return new String(Files.readAllBytes(Paths.get(uri)));
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public List<MethodInvocation> findMethodInvocations(String uri, int line) {
        return new ArrayList<>();
    }

    private String deriveClassName(String uri) {
        String path = uri;
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        if (base.endsWith(".java")) {
            base = base.substring(0, base.length() - ".java".length());
        }
        return base;
    }
}
