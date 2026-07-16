package com.example.envgovernance;

import com.example.envgovernance.contract.ContractViolation;
import com.example.envgovernance.contract.EnvContract;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registro estático do contrato de configuração ativo e suas violações.
 * <p>
 * Populado em duas fases no pipeline de {@code EnvironmentPostProcessor}:
 * <ol>
 *   <li>+16 {@code EnvContractScanner} — armazena o {@link EnvContract} carregado do classpath.</li>
 *   <li>+21 {@code EnvContractValidationEnvironmentPostProcessor} — armazena as
 *       {@link ContractViolation}s detectadas após resolução completa do ambiente.</li>
 * </ol>
 * O relatório ({@code EnvVarUsageReporter}) e o endpoint ({@code EnvGovernanceEndpoint})
 * consultam este registro após o contexto estar pronto.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
public final class ContractRegistry {

	private static final AtomicReference<EnvContract> CONTRACT =
			new AtomicReference<>(EnvContract.empty());

	private static volatile List<ContractViolation> VIOLATIONS = List.of();

	private ContractRegistry() {
	}

	public static EnvContract getContract() {
		return CONTRACT.get();
	}

	public static void setContract(EnvContract contract) {
		CONTRACT.set(contract != null ? contract : EnvContract.empty());
	}

	public static List<ContractViolation> getViolations() {
		return VIOLATIONS;
	}

	public static void setViolations(List<ContractViolation> violations) {
		VIOLATIONS = violations != null ? List.copyOf(violations) : List.of();
	}

	/** Limpa estado — usado em testes para isolar execuções. */
	public static void reset() {
		CONTRACT.set(EnvContract.empty());
		VIOLATIONS = List.of();
	}
}
