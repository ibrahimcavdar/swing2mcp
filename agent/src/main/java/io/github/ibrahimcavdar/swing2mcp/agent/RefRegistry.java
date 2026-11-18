package io.github.ibrahimcavdar.swing2mcp.agent;

import java.awt.Component;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Assigns stable, short ids ("e12") to components. A component keeps its ref for as
 * long as it is alive, so refs from an earlier snapshot stay valid.
 *
 * Components are told apart by identity, not equals(): some applications have components that
 * delegate equals/hashCode to another component (e.g. a window facade for an internal frame).
 */
final class RefRegistry {

    /** Weak key compared by identity. */
    private static final class Key extends WeakReference<Component> {
        final int hash;
        final String ref;

        Key(Component component, String ref, ReferenceQueue<Component> queue) {
            super(component, queue);
            this.hash = System.identityHashCode(component);
            this.ref = ref;
        }
    }

    private final Map<Integer, Key[]> byIdentity = new HashMap<Integer, Key[]>();
    private final Map<String, Key> byRef = new HashMap<String, Key>();
    private final ReferenceQueue<Component> collected = new ReferenceQueue<Component>();
    private int next = 1;

    synchronized String refFor(Component component) {
        purge();
        int hash = System.identityHashCode(component);
        Key[] bucket = byIdentity.get(hash);
        if (bucket != null) {
            for (Key key : bucket) {
                if (key.get() == component) {
                    return key.ref;
                }
            }
        }
        Key key = new Key(component, "e" + next++, collected);
        Key[] grown = bucket == null ? new Key[1] : java.util.Arrays.copyOf(bucket, bucket.length + 1);
        grown[grown.length - 1] = key;
        byIdentity.put(hash, grown);
        byRef.put(key.ref, key);
        return key.ref;
    }

    synchronized Component resolve(String ref) {
        Key key = byRef.get(ref);
        return key == null ? null : key.get();
    }

    private void purge() {
        for (Reference<? extends Component> gone; (gone = collected.poll()) != null; ) {
            Key key = (Key) gone;
            byRef.remove(key.ref);
            Key[] bucket = byIdentity.get(key.hash);
            if (bucket == null) {
                continue;
            }
            int kept = 0;
            Key[] rest = new Key[bucket.length];
            for (Key k : bucket) {
                if (k != key) {
                    rest[kept++] = k;
                }
            }
            if (kept == 0) {
                byIdentity.remove(key.hash);
            } else {
                byIdentity.put(key.hash, java.util.Arrays.copyOf(rest, kept));
            }
        }
    }
}
