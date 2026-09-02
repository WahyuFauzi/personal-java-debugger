package tool.java_debug_launcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

public class SimpleEvaluationProvider implements IEvaluationProvider {
    @Override
    public boolean isInEvaluation(ThreadReference thread) {
        return false;
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ThreadReference thread, int depth) {
        CompletableFuture<Value> future = new CompletableFuture<>();
        try {
            future.complete(SimpleEvaluator.evaluateInFrame(thread.frame(depth), expression));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CompletableFuture<Value> evaluate(String expression, ObjectReference thisContext, ThreadReference thread) {
        CompletableFuture<Value> future = new CompletableFuture<>();
        try {
            future.complete(SimpleEvaluator.evaluateOnObject(thisContext, thread, expression));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CompletableFuture<Value> evaluateForBreakpoint(IEvaluatableBreakpoint breakpoint, ThreadReference thread) {
        return unsupported();
    }

    @Override
    public CompletableFuture<Value> invokeMethod(ObjectReference thisContext, String methodName, String methodSignature,
            Value[] args, ThreadReference thread, boolean invokeSuper) {
        CompletableFuture<Value> future = new CompletableFuture<>();
        try {
            Method method = findMethod(thisContext, methodName, methodSignature);
            if (method == null) {
                future.completeExceptionally(
                        new NoSuchMethodException(methodName + methodSignature));
                return future;
            }
            List<Value> arguments =
                    args == null ? Collections.emptyList() : Arrays.asList(args);
            Value result = thisContext.invokeMethod(thread, method, arguments,
                    ObjectReference.INVOKE_SINGLE_THREADED);
            future.complete(result);
        } catch (InvocationException e) {
            future.completeExceptionally(new IllegalStateException(
                    "method threw in debuggee: " + e.exception()));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public void clearState(ThreadReference thread) {
    }

    private Method findMethod(ObjectReference obj, String name, String signature) {
        for (Method method : obj.referenceType().allMethods()) {
            if (method.name().equals(name) && method.signature().equals(signature)) {
                return method;
            }
        }
        return null;
    }

    private CompletableFuture<Value> unsupported() {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("evaluate is not supported by the standalone server"));
    }
}
