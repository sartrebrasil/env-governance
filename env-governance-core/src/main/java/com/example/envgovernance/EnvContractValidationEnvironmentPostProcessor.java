package com.example.envgovernance;

import com.example.envgovernance.contract.ContractViolation;
import com.example.envgovernance.source.SpringEnvVarSourceAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Valida os valores do ambiente resolvido contra o contrato declarativo e registra (ou lança)
 * as violações detectadas.
 *
 * <p>Ordem {@code HIGHEST_PRECEDENCE + 21}: após o
 * {@code RequiredEnvCheckEnvironmentPostProcessor} (+20), de forma que ausências já
 * tenham abortado o startup antes desta fase chegar; e após o
 * {@code EnvVarSourceLoaderPostProcessor} (+18), que garante que valores do Vault e
 * outras fontes externas já estejam injetados quando os validadores rodarem.
 *
 * <p>O comportamento em caso de violação de valor é controlado por
 * {@code env.governance.contract.fail-on-invalid} (default: {@code true}):
 * <ul>
 *   <li>{@code true} — lança {@link EnvContractValidationException}, abortando o startup.</li>
 *   <li>{@code false} — apenas registra as violações no {@link ContractRegistry}; o relatório
 *       do {@code ApplicationReadyEvent} as exibirá como {@code [INVALID]}.</li>
 * </ul>
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see ContractRegistry
 * @see EnvContractScanner
 */
public class EnvContractValidationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	private static final Log log = LogFactory.getLog(EnvContractValidationEnvironmentPostProcessor.class);

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!isActive(environment)) {
			return;
		}

		Map<String, String> flatEnv = SpringEnvVarSourceAdapter.flattenEnvironment(environment);
		List<ContractViolation> violations = ContractRegistry.getContract().validate(flatEnv);

		if (violations.isEmpty()) {
			return;
		}

		ContractRegistry.setViolations(violations);

		List<ContractViolation> invalid = violations.stream()
				.filter(v -> v.kind() == ContractViolation.Kind.INVALID)
				.toList();

		if (invalid.isEmpty()) {
			return; // apenas ausências, já reportadas pelo RequiredEnvCheck
		}

		boolean failOnInvalid = environment.getProperty(
				"env.governance.contract.fail-on-invalid", Boolean.class, true);

		if (!failOnInvalid) {
			log.warn("[ENV GOVERNANCE] " + invalid.size()
					+ " variável(is) com valor inválido detectada(s) (fail-on-invalid=false)");
			return;
		}

		throw new EnvContractValidationException(formatViolations(invalid), invalid);
	}

	private boolean isActive(ConfigurableEnvironment environment) {
		boolean enabled = environment.getProperty("env.governance.enabled", Boolean.class, true);
		boolean contractEnabled = environment.getProperty(
				"env.governance.contract.enabled", Boolean.class, true);
		return enabled && contractEnabled;
	}

	private String formatViolations(List<ContractViolation> violations) {
		String detail = violations.stream()
				.map(v -> String.format("  %-45s %s", v.name(), v.message()))
				.collect(Collectors.joining("\n"));
		return String.format(
				"%n===== ENV GOVERNANCE: VALORES DE VARIÁVEIS INVÁLIDOS (%d) =====%n%s%n%s%n"
						+ "Corrija os valores acima antes de iniciar a aplicação.",
				violations.size(), detail, "=".repeat(57));
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 21;
	}
}
