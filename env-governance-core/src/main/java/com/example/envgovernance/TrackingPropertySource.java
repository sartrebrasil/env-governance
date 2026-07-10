package com.example.envgovernance;

import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Decorator que intercepta toda leitura de propriedade e registra a chave em
 * {@link UsedPropertiesRegistry}. Use {@link #wrap(PropertySource)} para obter
 * a instância adequada: preserva {@link EnumerablePropertySource} quando o delegate
 * o implementa, evitando quebrar tooling interno do Spring que depende de enumeração.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
public class TrackingPropertySource extends PropertySource<PropertySource<?>> {

	TrackingPropertySource(PropertySource<?> delegate) {
		super(delegate.getName(), delegate);
	}

	@Override
	public Object getProperty(String name) {
		Object value = source.getProperty(name);
		if (value != null) {
			UsedPropertiesRegistry.record(name);
		}
		return value;
	}

	/**
	 * Envolve {@code source} com tracking, preservando {@link EnumerablePropertySource}
	 * quando o delegate o implementa.
	 */
	public static PropertySource<?> wrap(PropertySource<?> source) {
		if (source instanceof EnumerablePropertySource<?> enumerable) {
			return new TrackingEnumerablePropertySource(enumerable);
		}
		return new TrackingPropertySource(source);
	}

	private static final class TrackingEnumerablePropertySource
			extends EnumerablePropertySource<EnumerablePropertySource<?>> {

		TrackingEnumerablePropertySource(EnumerablePropertySource<?> delegate) {
			super(delegate.getName(), delegate);
		}

		@Override
		public String[] getPropertyNames() {
			return source.getPropertyNames();
		}

		@Override
		public Object getProperty(String name) {
			Object value = source.getProperty(name);
			if (value != null) {
				UsedPropertiesRegistry.record(name);
			}
			return value;
		}
	}
}
