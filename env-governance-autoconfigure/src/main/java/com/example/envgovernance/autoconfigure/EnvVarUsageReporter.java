package com.example.envgovernance.autoconfigure;

import com.example.envgovernance.ContractRegistry;
import com.example.envgovernance.DeclaredVarsRegistry;
import com.example.envgovernance.contract.ContractViolation;
import com.example.envgovernance.source.EnvVarSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Produz um diagnóstico de variáveis de ambiente no {@link ApplicationReadyEvent} em dois níveis.
 *
 * <h3>INFO — gaps acionáveis</h3>
 * <ul>
 *   <li>{@code [REQUIRED]} — placeholder {@code ${VAR}} sem default, {@code VAR} ausente em todas as fontes</li>
 *   <li>{@code [FALLBACK]}  — placeholder {@code ${VAR:default}}, {@code VAR} ausente (usando fallback)</li>
 *   <li>{@code [NO VALUE]}  — propriedade sem valor no YAML e sem variável correspondente em nenhuma fonte</li>
 *   <li>{@code [INVALID]}   — variáveis presentes mas com valor inválido conforme contrato declarativo</li>
 *   <li>{@code [UNUSED]}    — variáveis de ambiente sem correspondência em nenhuma propriedade (apenas acionáveis)</li>
 *   <li>{@code [SYSTEM]}    — vars de infraestrutura (S.O./JVM/CI) colapsadas em uma linha (modo {@code collapse})</li>
 * </ul>
 *
 * <h3>DEBUG — diagnóstico completo</h3>
 * <ul>
 *   <li>{@code [APPLICATION]} — todas as variáveis potenciais (chave + placeholders explícitos)</li>
 *   <li>{@code [ENVIRONMENT]} — todas as variáveis de todas as fontes ativas</li>
 *   <li>{@code [ACTIVE]}      — variáveis que estão sobrescrevendo propriedades da aplicação</li>
 *   <li>{@code [UNUSED]}      — variáveis sem correspondência (mesma lista do INFO)</li>
 * </ul>
 *
 * <p>Cada linha de diagnóstico indica a fonte de origem da variável, ex.:
 * {@code DB_PASSWORD  →  spring.datasource.password  [application.yml]  <vault[secret/data/myapp]>}
 *
 * @author Sartre Brasil
 * @since 1.0
 * @see EnvVarSourceRegistry
 */
public class EnvVarUsageReporter implements ApplicationListener<ApplicationReadyEvent> {

	private static final Logger log = LoggerFactory.getLogger(EnvVarUsageReporter.class);

	/** Quantidade de nomes exibidos na linha colapsada {@code [SYSTEM]}. */
	private static final int SYSTEM_SAMPLE_SIZE = 12;

	private final EnvGovernanceProperties properties;

	public EnvVarUsageReporter(EnvGovernanceProperties properties) {
		this.properties = properties;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		if (!properties.reportOnReady()) {
			return;
		}

		Map<String, DeclaredVarsRegistry.DeclaredVar> declared = DeclaredVarsRegistry.getAll();
		// varName → sourceName (first-wins por prioridade)
		Map<String, String> attributions = EnvVarSourceRegistry.getVarAttributions();
		Set<String> allEnvVarNames = attributions.keySet();

		Set<String> allEnvNormalized = allEnvVarNames.stream()
				.map(EnvVarNormalizer::normalize)
				.collect(Collectors.toSet());
		Set<String> declaredNormalized = declared.keySet().stream()
				.map(EnvVarNormalizer::normalize)
				.collect(Collectors.toSet());

		if (log.isDebugEnabled()) {
			logDebug(declared, attributions, allEnvVarNames, allEnvNormalized, declaredNormalized);
		}

		logInfo(declared, attributions, allEnvNormalized, allEnvVarNames, declaredNormalized);
	}

	// -------------------------------------------------------------------------
	// INFO: apenas gaps e vars não utilizadas
	// -------------------------------------------------------------------------

	private void logInfo(Map<String, DeclaredVarsRegistry.DeclaredVar> declared,
			Map<String, String> attributions,
			Set<String> allEnvNormalized,
			Set<String> allEnvVarNames,
			Set<String> declaredNormalized) {

		// [REQUIRED] placeholder sem default, var ausente em todas as fontes
		List<DeclaredVarsRegistry.DeclaredVar> required = declared.values().stream()
				.filter(v -> v.explicit() && !v.hasDefault())
				.filter(v -> !allEnvNormalized.contains(EnvVarNormalizer.normalize(v.envVarName())))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.toList();

		// [FALLBACK] placeholder com default, var ausente (usando valor de fallback)
		List<DeclaredVarsRegistry.DeclaredVar> fallback = declared.values().stream()
				.filter(v -> v.explicit() && v.hasDefault())
				.filter(v -> !allEnvNormalized.contains(EnvVarNormalizer.normalize(v.envVarName())))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.toList();

		// [NO VALUE] var key-derived sem valor no YAML e sem var correspondente em nenhuma fonte
		List<DeclaredVarsRegistry.DeclaredVar> noValue = declared.values().stream()
				.filter(v -> !v.explicit() && !v.hasYamlValue())
				.filter(v -> !allEnvNormalized.contains(EnvVarNormalizer.normalize(v.envVarName())))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.toList();

		// [INVALID] vars com valor inválido conforme contrato declarativo
		List<ContractViolation> invalid = ContractRegistry.getViolations().stream()
				.filter(v -> v.kind() == ContractViolation.Kind.INVALID)
				.sorted(Comparator.comparing(ContractViolation::name))
				.toList();

		// [UNUSED] vars sem correspondência em nenhuma propriedade da aplicação
		List<String> allUnused = allEnvVarNames.stream()
				.filter(k -> !declaredNormalized.contains(EnvVarNormalizer.normalize(k)))
				.sorted()
				.toList();

		// Separa o ruído de infraestrutura (S.O./JVM/CI) das órfãs realmente acionáveis.
		String mode = properties.systemVarMode() == null ? "collapse" : properties.systemVarMode().toLowerCase();
		boolean partition = !"full".equals(mode);

		List<String> orphans = partition
				? allUnused.stream()
						.filter(k -> !WellKnownSystemEnvVars.isSystemVar(k, properties.systemVarPatterns()))
						.toList()
				: allUnused;
		List<String> systemNoise = partition
				? allUnused.stream()
						.filter(k -> WellKnownSystemEnvVars.isSystemVar(k, properties.systemVarPatterns()))
						.toList()
				: List.of();

		boolean hasGaps = !required.isEmpty() || !fallback.isEmpty() || !noValue.isEmpty() || !invalid.isEmpty();
		boolean hasUnused = !orphans.isEmpty() && properties.reportOrphanVars();
		boolean hasSystem = !systemNoise.isEmpty() && properties.reportOrphanVars() && "collapse".equals(mode);

		if (!hasGaps && !hasUnused && !hasSystem) {
			log.info("[ENV GOVERNANCE] Sem gaps de configuração detectados.");
			return;
		}

		log.info("===== ENV GOVERNANCE: GAPS DE CONFIGURAÇÃO =====");

		if (!required.isEmpty()) {
			log.error("[REQUIRED] Variáveis obrigatórias ausentes em todas as fontes ({}):", required.size());
			required.forEach(v ->
					log.error("  {}  →  {}  [{}]",
							padRight(v.envVarName(), 45), v.propertyKey(), v.sourceFile()));
		}

		if (!fallback.isEmpty()) {
			log.warn("[FALLBACK] Variáveis ausentes em todas as fontes, usando valor padrão ({}):", fallback.size());
			fallback.forEach(v ->
					log.warn("  {}  →  {}  [{}]",
							padRight(v.envVarName(), 45), v.propertyKey(), v.sourceFile()));
		}

		if (!noValue.isEmpty()) {
			log.warn("[NO VALUE] Propriedades sem valor no YAML e sem variável em nenhuma fonte ({}):", noValue.size());
			noValue.forEach(v ->
					log.warn("  {}  →  {}  [{}]",
							padRight(v.envVarName(), 45), v.propertyKey(), v.sourceFile()));
		}

		if (!invalid.isEmpty()) {
			log.error("[INVALID] Variáveis com valor inválido conforme contrato ({}):", invalid.size());
			invalid.forEach(v ->
					log.error("  {}  →  {}",
							padRight(v.name(), 45), v.message()));
		}

		if (hasUnused) {
			int limit = properties.orphanReportLimit();
			List<String> reported = orphans.size() > limit ? orphans.subList(0, limit) : orphans;
			String suffix = orphans.size() > limit
					? " (mostrando primeiras " + limit + " de " + orphans.size() + ")"
					: "";
			log.warn("[UNUSED] Variáveis sem correspondência na aplicação ({}){}:", orphans.size(), suffix);
			reported.forEach(k ->
					log.warn("  {}  <{}>", padRight(k, 45), attributions.getOrDefault(k, "?")));
		}

		if (hasSystem) {
			int sample = Math.min(systemNoise.size(), SYSTEM_SAMPLE_SIZE);
			String names = String.join(", ", systemNoise.subList(0, sample));
			String suffix = systemNoise.size() > sample ? ", … (lista completa em DEBUG)" : "";
			log.info("[SYSTEM] {} variáveis de S.O./runner ignoradas: {}{}",
					systemNoise.size(), names, suffix);
		}

		log.info("=================================================");
	}

	// -------------------------------------------------------------------------
	// DEBUG: diagnóstico completo
	// -------------------------------------------------------------------------

	private void logDebug(Map<String, DeclaredVarsRegistry.DeclaredVar> declared,
			Map<String, String> attributions,
			Set<String> allEnvVarNames,
			Set<String> allEnvNormalized,
			Set<String> declaredNormalized) {

		List<DeclaredVarsRegistry.DeclaredVar> appVars = declared.values().stream()
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.toList();

		List<String> activeOverrides = allEnvVarNames.stream()
				.filter(k -> declaredNormalized.contains(EnvVarNormalizer.normalize(k)))
				.sorted()
				.toList();

		List<String> unused = allEnvVarNames.stream()
				.filter(k -> !declaredNormalized.contains(EnvVarNormalizer.normalize(k)))
				.sorted()
				.toList();

		log.debug("===== ENV GOVERNANCE: DIAGNÓSTICO COMPLETO (startup) =====");

		log.debug("[APPLICATION] Variáveis de ambiente potenciais ({} var(s)):", appVars.size());
		if (appVars.isEmpty()) {
			log.debug("  (nenhuma)");
		} else {
			appVars.forEach(v -> {
				String origin = v.explicit()
						? (v.hasDefault() ? "[${} optional]" : "[${} required]")
						: (v.hasYamlValue() ? "[key]         " : "[key/no-value]");
				log.debug("  {}  {}  →  {}  [{}]",
						padRight(v.envVarName(), 45), origin, v.propertyKey(), v.sourceFile());
			});
		}

		log.debug("[ENVIRONMENT] Variáveis de todas as fontes ativas ({} var(s)):", allEnvVarNames.size());
		allEnvVarNames.stream().sorted().forEach(k ->
				log.debug("  {}  <{}>", padRight(k, 45), attributions.getOrDefault(k, "?")));

		log.debug("[ACTIVE] Substituições ativas: fontes sobrescrevendo propriedades da aplicação ({} var(s)):",
				activeOverrides.size());
		if (activeOverrides.isEmpty()) {
			log.debug("  (nenhuma)");
		} else {
			activeOverrides.forEach(k -> {
				DeclaredVarsRegistry.DeclaredVar matched = findByNormalized(declared, k);
				String source = attributions.getOrDefault(k, "?");
				if (matched != null) {
					log.debug("  {}  →  {}  [{}]  <{}>",
							padRight(k, 45), matched.propertyKey(), matched.sourceFile(), source);
				} else {
					log.debug("  {}  <{}>", padRight(k, 45), source);
				}
			});
		}

		log.debug("[UNUSED] Variáveis sem correspondência na aplicação ({} var(s)):", unused.size());
		if (unused.isEmpty()) {
			log.debug("  (nenhuma)");
		} else {
			int limit = properties.orphanReportLimit();
			List<String> reported = unused.size() > limit ? unused.subList(0, limit) : unused;
			if (unused.size() > limit) {
				log.debug("  (mostrando primeiras {} de {})", limit, unused.size());
			}
			reported.forEach(k ->
					log.debug("  {}  <{}>", padRight(k, 45), attributions.getOrDefault(k, "?")));
		}

		log.debug("==========================================================");
	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	private DeclaredVarsRegistry.DeclaredVar findByNormalized(
			Map<String, DeclaredVarsRegistry.DeclaredVar> declared, String envVar) {
		String norm = EnvVarNormalizer.normalize(envVar);
		return declared.values().stream()
				.filter(v -> EnvVarNormalizer.normalize(v.envVarName()).equals(norm))
				.findFirst()
				.orElse(null);
	}

	private String padRight(String s, int width) {
		return s.length() >= width ? s : s + " ".repeat(width - s.length());
	}
}
