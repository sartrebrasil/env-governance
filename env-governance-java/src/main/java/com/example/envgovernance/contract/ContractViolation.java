package com.example.envgovernance.contract;

/**
 * Uma violação do contrato de configuração detectada ao avaliar o ambiente.
 *
 * @param name    nome da variável envolvida
 * @param kind    categoria da violação
 * @param message mensagem legível descrevendo o problema
 * @param value   valor observado quando aplicável ({@code null} para ausências)
 * @author Sartre Brasil
 * @since 2.2
 * @see EnvContract#validate(java.util.Map)
 */
public record ContractViolation(String name, Kind kind, String message, String value) {

	/**
	 * Categoria de uma {@link ContractViolation}.
	 */
	public enum Kind {

		/** Variável obrigatória (estática) ausente. */
		MISSING,

		/** Variável presente, mas com valor que falhou em um {@link ValueValidator}. */
		INVALID,

		/** Variável exigida por uma {@link ConditionalRequirement} satisfeita, porém ausente. */
		CONDITIONAL_MISSING
	}
}
