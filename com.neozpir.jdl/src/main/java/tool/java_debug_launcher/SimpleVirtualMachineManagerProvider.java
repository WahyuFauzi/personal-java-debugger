package tool.java_debug_launcher;

import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachineManager;

public class SimpleVirtualMachineManagerProvider implements IVirtualMachineManagerProvider {
    @Override
    public VirtualMachineManager getVirtualMachineManager() {
        return Bootstrap.virtualMachineManager();
    }
}
