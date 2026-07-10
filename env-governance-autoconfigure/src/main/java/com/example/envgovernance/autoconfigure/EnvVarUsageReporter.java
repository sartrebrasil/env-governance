package com.example.envgovernance.autoconfigure;

import com.example.envgovernance.DeclaredVarsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Produz um diagnóstico de variáveis de ambiente no {@link ApplicationReadyEvent} em dois níveis:
 *
 * <h3>INFO — gaps acionáveis</h3>
 * <ul>
 *   <li>{@code [REQUIRED]} — placeholder {@code ${VAR}} sem default, {@code VAR} ausente no SO/container</li>
 *   <li>{@code [FALLBACK]}  — placeholder {@code ${VAR:default}}, {@code VAR} ausente (usando fallback)</li>
 *   <li>{@code [NO VALUE]}  — propriedade sem valor no YAML e sem variável de ambiente correspondente</li>
 *   <li>{@code [UNUSED]}    — variáveis do SO/container sem correspondência em nenhuma propriedade</li>
 * </ul>
 *
 * <h3>DEBUG — diagnóstico completo</h3>
 * <ul>
 *   <li>{@code [APPLICATION]} — todas as variáveis potenciais (chave + placeholders explícitos)</li>
 *   <li>{@code [ENVIRONMENT]} — todas as variáveis do SO/container</li>
 *   <li>{@code [ACTIVE]}      — variáveis do SO que estão sobrescrevendo propriedades da aplicação</li>
 *   <li>{@code [UNUSED]}      — variáveis do SO sem correspondência (mesma lista do INFO)</li>
 * </ul>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
public class EnvVarUsageReporter implements ApplicationListener<ApplicationReadyEvent> {

	private static final Logger log = LoggerFactory.getLogger(EnvVarUsageReporter.class);

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
		Set<String> osEnv = System.getenv().keySet();
		Set<String> declaredNormalized = declared.keySet().stream()
				.map(this::normalize)
				.collect(Collectors.toSet());

		if (log.isDebugEnabled()) {
			logDebug(declared, osEnv, declaredNormalized);
		}

		logInfo(declared, osEnv, declaredNormalized);
	}

	// -------------------------------------------------------------------------
	// INFO: apenas gaps e vars não utilizadas
	// -------------------------------------------------------------------------

	private void logInfo(Map<String, DeclaredVarsRegistry.DeclaredVar> declared,
			Set<String> osEnv, Set<String> declaredNormalized) {

		// [REQUIRED] placeholder sem default, var ausente no SO
		List<DeclaredVarsRegistry.DeclaredVar> required = declared.values().stream()
				.filter(v -> v.explicit() && !v.hasDefault())
				.filter(v -> osEnv.stream().noneMatch(k -> normalize(k).equals(normalize(v.envVarName()))))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.toList();

		// [FALLBACK] placeholder com default, var ausente no SO (usando valor de fallback)
		List<DeclaredVarsRegistry.DeclaredVar> fallback = declared.values().stream()
				.filter(v -> v.explicit() && v.hasDefault())
				.filter(v -> osEnv.stream().noneMatch(k -> normalize(k).equals(normalize(v.envVarName()))))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.toList();

		// [NO VALUE] var key-derived sem valor no YAML e sem var de ambiente correspondente
		List<DeclaredVarsRegistry.DeclaredVar> noValue = declared.values().stream()
				.filter(v -> !v.explicit() && !v.hasYamlValue())
				.filter(v -> osEnv.stream().noneMatch(k -> normalize(k).equals(normalize(v.envVarName()))))
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.toList();

		// [UNUSED] vars do SO sem correspondência em nenhuma propriedade da aplicação
		List<String> unused = osEnv.stream()
				.filter(k -> !declaredNormalized.contains(normalize(k)))
				.sorted()
				.toList();

		boolean hasGaps = !required.isEmpty() || !fallback.isEmpty() || !noValue.isEmpty();
		boolean hasUnused = !unused.isEmpty() && properties.reportOrphanVars();

		if (!hasGaps && !hasUnused) {
			log.info("[ENV GOVERNANCE] Sem gaps de configuração detectados.");
			return;
		}

		log.info("===== ENV GOVERNANCE: GAPS DE CONFIGURAÇÃO =====");

		if (!required.isEmpty()) {
			log.error("[REQUIRED] Variáveis obrigatórias ausentes no SO/container ({}):", required.size());
			required.forEach(v ->
					log.error("  {}  →  {}  [{}]", padRight(v.envVarName(), 45), v.propertyKey(), v.sourceFile()));
		}

		if (!fallback.isEmpty()) {
			log.warn("[FALLBACK] Variáveis ausentes no SO/container, usando valor padrão ({}):", fallback.size());
			fallback.forEach(v ->
					log.warn("  {}  →  {}  [{}]", padRight(v.envVarName(), 45), v.propertyKey(), v.sourceFile()));
		}

		if (!noValue.isEmpty()) {
			log.warn("[NO VALUE] Propriedades sem valor no YAML e sem variável de ambiente ({}):", noValue.size());
			noValue.forEach(v ->
					log.warn("  {}  →  {}  [{}]", padRight(v.envVarName(), 45), v.propertyKey(), v.sourceFile()));
		}

		if (hasUnused) {
			int limit = properties.orphanReportLimit();
			List<String> reported = unused.size() > limit ? unused.subList(0, limit) : unused;
			String suffix = unused.size() > limit
					? " (mostrando primeiras " + limit + " de " + unused.size() + ")"
					: "";
			log.warn("[UNUSED] Variáveis do SO/container não utilizadas pela aplicação ({}){}:",
					unused.size(), suffix);
			reported.forEach(k -> log.warn("  {}", k));
		}

		log.info("=================================================");
	}

	// -------------------------------------------------------------------------
	// DEBUG: diagnóstico completo
	// -------------------------------------------------------------------------

	private void logDebug(Map<String, DeclaredVarsRegistry.DeclaredVar> declared,
			Set<String> osEnv, Set<String> declaredNormalized) {

		List<DeclaredVarsRegistry.DeclaredVar> appVars = declared.values().stream()
				.sorted((a, b) -> a.envVarName().compareTo(b.envVarName()))
				.toList();

		List<String> activeOverrides = osEnv.stream()
				.filter(k -> declaredNormalized.contains(normalize(k)))
				.sorted()
				.toList();

		List<String> unused = osEnv.stream()
				.filter(k -> !declaredNormalized.contains(normalize(k)))
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

		log.debug("[ENVIRONMENT] Variáveis definidas no SO/container ({} var(s)):", osEnv.size());
		osEnv.stream().sorted().forEach(k -> log.debug("  {}", k));

		log.debug("[ACTIVE] Substituições ativas: SO sobrescrevendo propriedades da aplicação ({} var(s)):",
				activeOverrides.size());
		if (activeOverrides.isEmpty()) {
			log.debug("  (nenhuma)");
		} else {
			activeOverrides.forEach(k -> {
				DeclaredVarsRegistry.DeclaredVar matched = findByNormalized(declared, k);
				if (matched != null) {
					log.debug("  {}  →  {}  [{}]", padRight(k, 45), matched.propertyKey(), matched.sourceFile());
				} else {
					log.debug("  {}", k);
				}
			});
		}

		log.debug("[UNUSED] Variáveis do SO não utilizadas pela aplicação ({} var(s)):", unused.size());
		if (unused.isEmpty()) {
			log.debug("  (nenhuma)");
		} else {
			int limit = properties.orphanReportLimit();
			List<String> reported = unused.size() > limit ? unused.subList(0, limit) : unused;
			String suffix = unused.size() > limit
					? " (mostrando primeiras " + limit + " de " + unused.size() + ")"
					: "";
			if (!suffix.isEmpty()) log.debug("  {}", suffix.trim());
			reported.forEach(k -> log.debug("  {}", k));
		}

		log.debug("==========================================================");
	}

	// -------------------------------------------------------------------------
	// helpers
	// -------------------------------------------------------------------------

	private String normalize(String key) {
		return key.toUpperCase().replace('-', '_').replace('.', '_');
	}

	private DeclaredVarsRegistry.DeclaredVar findByNormalized(
			Map<String, DeclaredVarsRegistry.DeclaredVar> declared, String osVar) {
		String norm = normalize(osVar);
		return declared.values().stream()
				.filter(v -> normalize(v.envVarName()).equals(norm))
				.findFirst()
				.orElse(null);
	}

	private String padRight(String s, int width) {
		return s.length() >= width ? s : s + " ".repeat(width - s.length());
	}
}
