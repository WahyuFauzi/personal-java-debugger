package tool.java_debug_launcher;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.microsoft.java.debug.core.adapter.HotCodeReplaceEvent;
import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;

import io.reactivex.Observable;

public class SimpleHotCodeReplaceProvider implements IHotCodeReplaceProvider {
    @Override
    public void onClassRedefined(Consumer<List<String>> consumer) {
    }

    @Override
    public CompletableFuture<List<String>> redefineClasses() {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public Observable<HotCodeReplaceEvent> getEventHub() {
        return Observable.empty();
    }
}
