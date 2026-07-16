package com.example.envgovernance.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 * Propriedades de configuração do env-governance.
 *
 * <pre>
 * env:
 *   governance:
 *     enabled: true                   # desliga toda a lib
 *     fail-on-missing: true           # false em perfis de teste — desabilita o early check
 *     report-on-ready: true           # loga diagnóstico no ApplicationReadyEvent
 *     report-orphan-vars: true        # inclui seção [UNUSED] no relatório INFO
 *     orphan-report-limit: 50         # trunca a lista de vars não utilizadas para evitar spam
 *     system-var-mode: collapse       # collapse | hide | full — como tratar vars de S.O./runner em [UNUSED]
 *     system-var-patterns: []         # padrões extras p/ classificar como S.O.: FOO* (prefixo) ou FOO (exato)
 *     contract:
 *       enabled: true                 # habilita a validação do contrato declarativo
 *       location: classpath:env-governance.yml  # localização do arquivo de contrato
 *       fail-on-invalid: true         # lança exceção se houver valores inválidos
 *       strict-unknown-vars: false    # falha em vars desconhecidas (não declaradas no contrato)
 * </pre>
 *
 * <p>{@code system-var-mode} controla como variáveis de infraestrutura (S.O., JVM, Maven,
 * Kubernetes, runners de CI) aparecem no relatório {@code [UNUSED]}:
 * <ul>
 *   <li>{@code collapse} (padrão) — colapsa em uma única linha {@code [SYSTEM]} com contagem
 *       e amostra; lista completa apenas em DEBUG;</li>
 *   <li>{@code hide} — remove totalmente do relatório INFO;</li>
 *   <li>{@code full} — lista cada uma junto das demais órfãs (comportamento legado).</li>
 * </ul>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@ConfigurationProperties(prefix = "env.governance")
public record EnvGovernanceProperties(
		@DefaultValue("true")  boolean enabled,
		@DefaultValue("true")  boolean failOnMissing,
		@DefaultValue("true")  boolean reportOnReady,
		@DefaultValue("true")  boolean reportOrphanVars,
		@DefaultValue("50")    int orphanReportLimit,
		@DefaultValue({"PASSWORD", "SECRET", "TOKEN", "KEY", "CREDENTIAL"}) Set<String> sensitiveHints,
		@DefaultValue("collapse") String systemVarMode,
		@DefaultValue({}) Set<String> systemVarPatterns,
		Contract contract
) {

	/**
	 * Configurações do contrato declarativo de configuração ({@code env.governance.contract.*}).
	 *
	 * @param enabled           habilita a validação do contrato
	 * @param location          localização do arquivo de contrato no classpath
	 * @param failOnInvalid     lança exceção se houver valores inválidos no contrato
	 * @param strictUnknownVars falha em variáveis presentes no ambiente mas não declaradas no contrato
	 */
	public record Contract(
			@DefaultValue("true")  boolean enabled,
			@DefaultValue("classpath:env-governance.yml") String location,
			@DefaultValue("true")  boolean failOnInvalid,
			@DefaultValue("false") boolean strictUnknownVars
	) {}
}
