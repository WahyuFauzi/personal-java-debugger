package tool.java_debug_launcher;

import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.microsoft.java.debug.core.adapter.ProtocolServer;
import com.microsoft.java.debug.core.adapter.ProviderContext;

public class ServerLauncher {
    public static void main(String[] args) {
        ProviderContext context = new ProviderContext();
        context.registerProvider(IVirtualMachineManagerProvider.class, new SimpleVirtualMachineManagerProvider());
        context.registerProvider(ISourceLookUpProvider.class, new SimpleSourceLookUpProvider());
        context.registerProvider(IHotCodeReplaceProvider.class, new SimpleHotCodeReplaceProvider());
        context.registerProvider(IEvaluationProvider.class, new SimpleEvaluationProvider());
        context.registerProvider(ICompletionsProvider.class, new SimpleCompletionsProvider());
        ProtocolServer server = new ProtocolServer(System.in, System.out, context);
        server.run();
    }
}
