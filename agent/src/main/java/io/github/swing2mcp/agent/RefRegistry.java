package io.github.swing2mcp.agent;

import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Assigns stable, short ids ("e12") to components. A component keeps its ref for as
 * long as it is alive, so refs from an earlier snapshot stay valid.
 */
final class RefRegistry {

    private final Map<Component, String> refs = new WeakHashMap<Component, String>();
    private final Map<String, WeakReference<Component>> components = new HashMap<String, WeakReference<Component>>();
    private int next = 1;

    synchronized String refFor(Component component) {
        String ref = refs.get(component);
        if (ref == null) {
            ref = "e" + next++;
            refs.put(component, ref);
            components.put(ref, new WeakReference<Component>(component));
            if (components.size() > 2 * refs.size() + 1000) {
                purge();
            }
        }
        return ref;
    }

    synchronized Component resolve(String ref) {
        WeakReference<Component> reference = components.get(ref);
        return reference == null ? null : reference.get();
    }

    private void purge() {
        Iterator<WeakReference<Component>> it = components.values().iterator();
        while (it.hasNext()) {
            if (it.next().get() == null) {
                it.remove();
            }
        }
    }
}
