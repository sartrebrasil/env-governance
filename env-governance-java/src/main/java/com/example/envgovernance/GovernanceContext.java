package com.example.envgovernance;

import com.example.envgovernance.spi.EnvVarSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Entry point para verificação de variáveis de ambiente em aplicações não-Spring Boot.
 * <p>
 * Fontes de variáveis são descobertas automaticamente via {@link ServiceLoader} (arquivo
 * {@code META-INF/services/com.example.envgovernance.spi.EnvVarSource} no classpath)
 * e podem ser complementadas com fontes explícitas e arquivos de configuração.
 *
 * <h3>Uso típico</h3>
 * <pre>{@code
 * public static void main(String[] args) {
 *     GovernanceContext.builder()
 *         .require("DB_URL")
 *         .require("API_KEY")
 *         .fromDotEnv(Path.of(".env"))   // opcional
 *         .build()
 *         .verify();
 *     // aplicação inicia normalmente
 * }
 * }</pre>
 *
 * <h3>Ordem de prioridade (maior para menor)</h3>
 * <ol>
 *   <li>Variáveis do S.O. ({@code System.getenv()})</li>
 *   <li>Fontes dinâmicas (Vault, etc.) na ordem de {@code getOrder()}</li>
 *   <li>Arquivos ({@code .env}, {@code .properties}) na ordem de registro</li>
 * </ol>
 *
 * @author Sartre Brasil
 * @since 2.0
 * @see EnvVarSource
 * @see GovernanceResult
 */
public final class GovernanceContext {

	private final List<String> requiredVarNames;
	private final List<EnvVarSource> sources;
	private final List<Map<String, String>> fileSources;

	private GovernanceContext(List<String> requiredVarNames,
	                          List<EnvVarSource> sources,
	                          List<Map<String, String>> fileSources) {
		this.requiredVarNames = List.copyOf(requiredVarNames);
		this.sources = List.copyOf(sources);
		this.fileSources = List.copyOf(fileSources);
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Executa a verificação e retorna o resultado sem lançar exceção.
	 */
	public GovernanceResult check() {
		// Base: S.O. tem maior prioridade
		Map<String, String> merged = new LinkedHashMap<>(System.getenv());

		// Fontes dinâmicas: putIfAbsent preserva prioridade do S.O. e de fontes anteriores
		for (EnvVarSource source : sources) {
			if (!source.isAvailable(merged)) continue;
			Map<String, String> loaded = source.load(merged);
			loaded.forEach(merged::putIfAbsent);
		}

		// Arquivos de configuração: menor prioridade
		fileSources.forEach(m -> m.forEach(merged::putIfAbsent));

		List<String> missing = requiredVarNames.stream()
				.filter(name -> !merged.containsKey(name))
				.sorted()
				.toList();

		return new GovernanceResult(missing, Map.copyOf(merged), buildAttributions());
	}

	/**
	 * Executa a verificação e lança {@link MissingRequiredEnvironmentVariablesException}
	 * se alguma variável obrigatória estiver ausente.
	 */
	public void verify() {
		GovernanceResult result = check();
		if (!result.hasGaps()) return;

		String detail = result.missingRequired().stream()
				.map(name -> "  " + name)
				.collect(Collectors.joining("\n"));

		throw new MissingRequiredEnvironmentVariablesException(
				String.format("%n===== ENV GOVERNANCE: VARIÁVEIS OBRIGATÓRIAS AUSENTES (%d) =====%n%s%n",
						result.missingRequired().size(), detail),
				result.missingRequired());
	}

	private Map<String, String> buildAttributions() {
		Map<String, String> attributions = new LinkedHashMap<>();
		for (EnvVarSource source : sources) {
			for (String varName : source.getVarNames()) {
				attributions.putIfAbsent(varName, source.name());
			}
		}
		return Map.copyOf(attributions);
	}

	// -------------------------------------------------------------------------
	// Builder
	// -------------------------------------------------------------------------

	/**
	 * Builder fluente para {@link GovernanceContext}.
	 */
	public static final class Builder {

		private final List<String> requiredVarNames = new ArrayList<>();
		private final List<EnvVarSource> explicitSources = new ArrayList<>();
		private final List<Map<String, String>> fileSources = new ArrayList<>();
		private boolean discoverSources = true;

		private Builder() {}

		/** Declara uma variável de ambiente obrigatória. */
		public Builder require(String varName) {
			requiredVarNames.add(varName);
			return this;
		}

		/** Registra uma {@link EnvVarSource} explicitamente, sem passar pelo ServiceLoader. */
		public Builder addSource(EnvVarSource source) {
			explicitSources.add(source);
			return this;
		}

		/**
		 * Carrega variáveis de um arquivo {@code .env} (KEY=VALUE).
		 * Ignora silenciosamente se o arquivo não existir.
		 */
		public Builder fromDotEnv(Path file) {
			if (!file.toFile().exists()) return this;
			try {
				fileSources.add(EnvFileReader.readDotEnv(file));
			} catch (IOException e) {
				throw new UncheckedIOException("Falha ao ler arquivo .env: " + file, e);
			}
			return this;
		}

		/**
		 * Carrega variáveis de um arquivo {@code .properties}.
		 * Ignora silenciosamente se o arquivo não existir.
		 */
		public Builder fromProperties(Path file) {
			if (!file.toFile().exists()) return this;
			try {
				fileSources.add(EnvFileReader.readProperties(file));
			} catch (IOException e) {
				throw new UncheckedIOException("Falha ao ler arquivo .properties: " + file, e);
			}
			return this;
		}

		/**
		 * Desabilita a descoberta automática de fontes via {@link ServiceLoader}.
		 * Use quando quiser controle total sobre quais fontes são usadas.
		 */
		public Builder withoutServiceLoaderDiscovery() {
			this.discoverSources = false;
			return this;
		}

		public GovernanceContext build() {
			List<EnvVarSource> allSources = new ArrayList<>(explicitSources);
			if (discoverSources) {
				ServiceLoader.load(EnvVarSource.class).stream()
						.map(ServiceLoader.Provider::get)
						// evita duplicatas com fontes explícitas (nome é o discriminador)
						.filter(s -> explicitSources.stream().noneMatch(e -> e.name().equals(s.name())))
						.forEach(allSources::add);
			}
			allSources.sort(Comparator.comparingInt(EnvVarSource::getOrder));
			return new GovernanceContext(requiredVarNames, allSources, fileSources);
		}
	}
}
