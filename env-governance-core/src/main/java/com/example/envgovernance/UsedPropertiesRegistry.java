package com.example.envgovernance;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro estático das chaves de propriedade efetivamente lidas em runtime,
 * alimentado pelo {@link TrackingPropertySource}.
 *
 * @author Sartre Brasil
 * @since 1.0
 * @see TrackingPropertySource
 */
public final class UsedPropertiesRegistry {

	private static final Set<String> USED_KEYS = ConcurrentHashMap.newKeySet();

	private UsedPropertiesRegistry() {}

	static void record(String key) {
		USED_KEYS.add(key);
	}

	public static Set<String> getUsedKeys() {
		return Set.copyOf(USED_KEYS);
	}
}
