package com.example.envgovernance;

import com.example.envgovernance.contract.ConditionalRequirement;
import com.example.envgovernance.contract.ContractViolation;
import com.example.envgovernance.contract.EnvContract;
import com.example.envgovernance.contract.ValueValidator;
import com.example.envgovernance.contract.Validators;
import com.example.envgovernance.contract.VarSpec;
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
import java.util.function.Predicate;
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
 *         .require("DB_URL").asUrl()
 *         .require("SERVER_PORT").asPort()
 *         .require("API_KEY")
 *         .requireIf("AUTH_METHOD=approle", "ROLE_ID", "SECRET_ID")
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
 * @see EnvContract
 */
public final class GovernanceContext {

	private final EnvContract contract;
	private final List<EnvVarSource> sources;
	private final List<Map<String, String>> fileSources;

	private GovernanceContext(EnvContract contract,
	                          List<EnvVarSource> sources,
	                          List<Map<String, String>> fileSources) {
		this.contract = contract;
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

		List<ContractViolation> violations = contract.validate(merged);
		List<String> missing = violations.stream()
				.filter(v -> v.kind() == ContractViolation.Kind.MISSING
						|| v.kind() == ContractViolation.Kind.CONDITIONAL_MISSING)
				.map(ContractViolation::name)
				.sorted()
				.toList();

		return new GovernanceResult(missing, Map.copyOf(merged), buildAttributions(), violations);
	}

	/**
	 * Executa a verificação e lança uma exceção se houver gaps:
	 * <ul>
	 *   <li>{@link EnvContractValidationException} — quando há ao menos uma violação de
	 *       <em>valor</em> ({@code INVALID}); carrega todas as violações.</li>
	 *   <li>{@link MissingRequiredEnvironmentVariablesException} — quando há apenas ausências
	 *       de obrigatórias, preservando a mensagem e o tipo anteriores.</li>
	 * </ul>
	 */
	public void verify() {
		GovernanceResult result = check();
		if (!result.hasGaps()) return;

		boolean hasInvalid = result.violations().stream()
				.anyMatch(v -> v.kind() == ContractViolation.Kind.INVALID);

		if (hasInvalid) {
			throw new EnvContractValidationException(formatViolations(result.violations()),
					result.violations());
		}

		String detail = result.missingRequired().stream()
				.map(name -> "  " + name)
				.collect(Collectors.joining("\n"));

		throw new MissingRequiredEnvironmentVariablesException(
				String.format("%n===== ENV GOVERNANCE: VARIÁVEIS OBRIGATÓRIAS AUSENTES (%d) =====%n%s%n",
						result.missingRequired().size(), detail),
				result.missingRequired());
	}

	private static String formatViolations(List<ContractViolation> violations) {
		String detail = violations.stream()
				.map(v -> "  [" + v.kind() + "] " + v.message())
				.collect(Collectors.joining("\n"));
		return String.format("%n===== ENV GOVERNANCE: CONTRATO DE CONFIGURAÇÃO VIOLADO (%d) =====%n%s%n",
				violations.size(), detail);
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
	 * <p>
	 * {@link #require(String)} e {@link #optional(String)} retornam um {@link VarSpecBuilder}
	 * que permite anexar restrições de valor àquela variável (ex.: {@code .asPort()}); os demais
	 * métodos do builder continuam acessíveis a partir dele, então as cadeias antigas
	 * ({@code .require("A").require("B").build()}) permanecem válidas.
	 */
	public static final class Builder {

		private final List<VarSpec> specs = new ArrayList<>();
		private final List<ConditionalRequirement> conditionals = new ArrayList<>();
		private final List<EnvVarSource> explicitSources = new ArrayList<>();
		private final List<Map<String, String>> fileSources = new ArrayList<>();
		private boolean discoverSources = true;

		private Builder() {}

		/** Declara uma variável de ambiente obrigatória e abre restrições de valor para ela. */
		public VarSpecBuilder require(String varName) {
			return new VarSpecBuilder(this, varName, true);
		}

		/**
		 * Declara uma variável opcional (não falha se ausente), mas cujo valor, quando presente,
		 * deve satisfazer as restrições anexadas.
		 *
		 * @since 2.2
		 */
		public VarSpecBuilder optional(String varName) {
			return new VarSpecBuilder(this, varName, false);
		}

		/**
		 * Registra um requisito condicional a partir de uma igualdade simples.
		 *
		 * @param condition        expressão no formato {@code "CHAVE=valor"}
		 * @param requiredWhenTrue variáveis obrigatórias quando a condição é satisfeita
		 * @since 2.2
		 */
		public Builder requireIf(String condition, String... requiredWhenTrue) {
			conditionals.add(parseCondition(condition, requiredWhenTrue));
			return this;
		}

		/**
		 * Registra um requisito condicional com predicado arbitrário sobre o ambiente resolvido.
		 *
		 * @since 2.2
		 */
		public Builder requireIf(Predicate<Map<String, String>> condition,
		                         String description,
		                         String... requiredWhenTrue) {
			conditionals.add(new ConditionalRequirement(condition, List.of(requiredWhenTrue), description));
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

			// Contrato base do builder; receiver wins no merge
			EnvContract contract = new EnvContract(specs, conditionals);

			// Merge de contribuições das fontes (builder/arquivo têm precedência sobre SPI defaults)
			List<VarSpec> spiSpecs = new ArrayList<>();
			List<ConditionalRequirement> spiConds = new ArrayList<>();
			allSources.forEach(s -> {
				spiSpecs.addAll(s.contributedSpecs());
				spiConds.addAll(s.contributedConditionals());
			});
			if (!spiSpecs.isEmpty() || !spiConds.isEmpty()) {
				contract = contract.merge(new EnvContract(spiSpecs, spiConds));
			}

			return new GovernanceContext(contract, allSources, fileSources);
		}

		// Registro interno de uma spec finalizada pelo VarSpecBuilder.
		private void addSpec(VarSpec spec) {
			specs.add(spec);
		}

		private static ConditionalRequirement parseCondition(String condition, String... requiredWhenTrue) {
			int idx = condition == null ? -1 : condition.indexOf('=');
			if (idx < 1) {
				throw new IllegalArgumentException(
						"Condição inválida (esperado \"CHAVE=valor\"): " + condition);
			}
			String key = condition.substring(0, idx);
			String value = condition.substring(idx + 1);
			return ConditionalRequirement.whenEquals(key, value, requiredWhenTrue);
		}
	}

	// -------------------------------------------------------------------------
	// VarSpecBuilder
	// -------------------------------------------------------------------------

	/**
	 * Sub-builder escopado a uma única variável, para anexar restrições de valor.
	 * <p>
	 * Todos os métodos terminais do {@link Builder} pai são reexpostos aqui: ao invocá-los,
	 * a spec em construção é finalizada no contrato antes de delegar — preservando a
	 * compatibilidade de código-fonte com as cadeias anteriores.
	 *
	 * @author Sartre Brasil
	 * @since 2.2
	 */
	public static final class VarSpecBuilder {

		private final Builder parent;
		private final String name;
		private final boolean required;
		private final List<ValueValidator> validators = new ArrayList<>();
		private String description = "";
		private boolean sensitive = false;
		private String type = "";

		private VarSpecBuilder(Builder parent, String name, boolean required) {
			this.parent = parent;
			this.name = name;
			this.required = required;
		}

		/** Exige um inteiro decimal. */
		public VarSpecBuilder asInt() {
			validators.add(Validators.integer());
			if (type.isEmpty()) type = "int";
			return this;
		}

		/** Exige uma porta TCP/UDP válida (1–65535). */
		public VarSpecBuilder asPort() {
			validators.add(Validators.port());
			if (type.isEmpty()) type = "port";
			return this;
		}

		/** Exige uma URL absoluta. */
		public VarSpecBuilder asUrl() {
			validators.add(Validators.url());
			if (type.isEmpty()) type = "url";
			return this;
		}

		/** Exige um booleano textual ({@code true}/{@code false}). */
		public VarSpecBuilder asBoolean() {
			validators.add(Validators.bool());
			if (type.isEmpty()) type = "boolean";
			return this;
		}

		/** Exige valor não vazio. */
		public VarSpecBuilder nonEmpty() {
			validators.add(Validators.nonEmpty());
			return this;
		}

		/** Exige que o valor esteja no conjunto informado. */
		public VarSpecBuilder oneOf(String... allowed) {
			validators.add(Validators.oneOf(allowed));
			return this;
		}

		/** Exige que o valor case totalmente com a expressão regular. */
		public VarSpecBuilder matches(String regex) {
			validators.add(Validators.regex(regex));
			return this;
		}

		/** Exige um inteiro maior ou igual a {@code bound}. */
		public VarSpecBuilder min(long bound) {
			validators.add(Validators.min(bound));
			return this;
		}

		/** Exige um inteiro menor ou igual a {@code bound}. */
		public VarSpecBuilder max(long bound) {
			validators.add(Validators.max(bound));
			return this;
		}

		/** Anexa um validador customizado. */
		public VarSpecBuilder withValidator(ValueValidator validator) {
			validators.add(validator);
			return this;
		}

		/** Define a descrição legível da variável (para relatórios). */
		public VarSpecBuilder describedAs(String description) {
			this.description = description;
			return this;
		}

		/** Marca a variável como sensível (valor mascarado em relatórios). */
		public VarSpecBuilder sensitive() {
			this.sensitive = true;
			return this;
		}

		private Builder flush() {
			parent.addSpec(new VarSpec(name, required, validators, description, sensitive, type));
			return parent;
		}

		// --- reexposição dos terminais do Builder pai (finalizam a spec atual) ---

		public VarSpecBuilder require(String varName) {
			return flush().require(varName);
		}

		public VarSpecBuilder optional(String varName) {
			return flush().optional(varName);
		}

		public Builder requireIf(String condition, String... requiredWhenTrue) {
			return flush().requireIf(condition, requiredWhenTrue);
		}

		public Builder requireIf(Predicate<Map<String, String>> condition,
		                         String description,
		                         String... requiredWhenTrue) {
			return flush().requireIf(condition, description, requiredWhenTrue);
		}

		public Builder addSource(EnvVarSource source) {
			return flush().addSource(source);
		}

		public Builder fromDotEnv(Path file) {
			return flush().fromDotEnv(file);
		}

		public Builder fromProperties(Path file) {
			return flush().fromProperties(file);
		}

		public Builder withoutServiceLoaderDiscovery() {
			return flush().withoutServiceLoaderDiscovery();
		}

		public GovernanceContext build() {
			return flush().build();
		}
	}
}
