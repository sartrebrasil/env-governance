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
 *     fail-on-missing: true           # false em perfis de teste
 *     report-on-ready: true           # loga diagnóstico no ApplicationReadyEvent
 *     report-orphan-vars: true        # inclui seção [WARN] de vars do SO não declaradas no YAML
 *     orphan-report-limit: 50         # trunca a lista de orphan vars para evitar spam
 *     sensitive-hints: PASSWORD,...   # palavras que mascaram o default no relatório
 * </pre>
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
		@DefaultValue({"PASSWORD", "SECRET", "TOKEN", "KEY", "CREDENTIAL"}) Set<String> sensitiveHints
) {}
