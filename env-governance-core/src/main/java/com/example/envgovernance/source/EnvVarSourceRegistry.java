package com.example.envgovernance.source;

import com.example.envgovernance.spi.EnvVarSource;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Registro estático das {@link EnvVarSource}s ativas, populado pelo
 * {@link EnvVarSourceLoaderPostProcessor} durante a fase de EPP.
 * <p>
 * Segue o mesmo padrão do {@link com.example.envgovernance.DeclaredVarsRegistry}: estático,
 * sem contexto Spring, com escrita restrospectiva (package-private) para evitar mutação
 * acidental por código de aplicação.
 *
 * @author Sartre Brasil
 * @since 1.1
 * @see EnvVarSourceLoaderPostProcessor
 */
public final class EnvVarSourceRegistry {

	// CopyOnWriteArrayList: escrito na fase EPP (single-thread), lido no evento
	// ApplicationReadyEvent (possivelmente concorrente) e no endpoint do Actuator.
	private static final CopyOnWriteArrayList<EnvVarSource> ACTIVE_SOURCES = new CopyOnWriteArrayList<>();

	private EnvVarSourceRegistry() {}

	/** Package-private: apenas {@link EnvVarSourceLoaderPostProcessor} escreve aqui. */
	static void register(EnvVarSource source) {
		ACTIVE_SOURCES.add(source);
	}

	/**
	 * Retorna as fontes ativas em ordem de prioridade (menor {@code getOrder()} primeiro),
	 * como snapshot seguro para iteração.
	 */
	public static List<EnvVarSource> getActiveSources() {
		return ACTIVE_SOURCES.stream()
				.sorted(Comparator.comparingInt(EnvVarSource::getOrder))
				.toList();
	}

	/**
	 * União dos nomes de variáveis de todas as fontes ativas.
	 * Substitui {@code System.getenv().keySet()} nos reporters.
	 */
	public static Set<String> getAllVarNames() {
		return ACTIVE_SOURCES.stream()
				.flatMap(s -> s.getVarNames().stream())
				.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Mapa {@code varName → sourceName}, first-wins (a fonte de maior prioridade leva a
	 * atribuição quando a variável existe em múltiplas fontes).
	 */
	public static Map<String, String> getVarAttributions() {
		Map<String, String> result = new LinkedHashMap<>();
		List<EnvVarSource> sorted = ACTIVE_SOURCES.stream()
				.sorted(Comparator.comparingInt(EnvVarSource::getOrder))
				.toList();
		for (EnvVarSource source : sorted) {
			for (String varName : source.getVarNames()) {
				result.putIfAbsent(varName, source.name());
			}
		}
		return Map.copyOf(result);
	}

	/** Para isolamento de testes — espelha {@code DeclaredVarsRegistry.reset()}. */
	public static void reset() {
		ACTIVE_SOURCES.clear();
	}
}
