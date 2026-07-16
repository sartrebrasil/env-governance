package com.example.envgovernance;

import com.example.envgovernance.contract.ContractViolation;

import java.util.List;

/**
 * Lançada quando o contrato de configuração é violado por <em>valor</em> — ou seja, quando
 * há ao menos uma variável presente cujo valor reprovou em um validador
 * ({@link ContractViolation.Kind#INVALID}).
 * <p>
 * Distingue-se de {@link MissingRequiredEnvironmentVariablesException}, que sinaliza apenas
 * <em>ausência</em> de variáveis obrigatórias. Manter dois tipos permite que scripts de deploy
 * já existentes continuem capturando a exceção de ausência sem mudança de comportamento.
 * As violações carregadas aqui incluem <strong>todas</strong> as detectadas (presença e valor),
 * não só as inválidas, para não perder contexto.
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see ContractViolation
 */
public class EnvContractValidationException extends RuntimeException {

	private final List<ContractViolation> violations;

	public EnvContractValidationException(String message, List<ContractViolation> violations) {
		super(message);
		this.violations = List.copyOf(violations);
	}

	/** Todas as violações de contrato detectadas, ordenadas por nome e categoria. */
	public List<ContractViolation> getViolations() {
		return violations;
	}
}
