package com.example.envgovernance.contract;

/**
 * Restrição aplicável ao <em>valor</em> de uma variável de ambiente presente no contrato.
 * <p>
 * É a unidade de validação de conteúdo — complementa a verificação de <em>presença</em>.
 * Implementações não são invocadas para variáveis ausentes: cabe ao contrato decidir
 * se a ausência é uma falha (via {@link VarSpec#required()} ou
 * {@link ConditionalRequirement}). Assume-se, portanto, que {@code value} nunca é
 * {@code null} quando {@link #validate(String, String)} é chamado.
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see Validators
 */
@FunctionalInterface
public interface ValueValidator {

	/**
	 * Valida o valor de uma variável.
	 *
	 * @param name  nome da variável (usado para compor a mensagem)
	 * @param value valor resolvido, nunca {@code null}
	 * @return {@link ValidationResult#ok()} ou {@link ValidationResult#invalid(String)}
	 */
	ValidationResult validate(String name, String value);

	/**
	 * Rótulo curto da restrição, exibido em relatórios (ex.: {@code "port"}, {@code "oneOf[a, b]"}).
	 */
	default String describe() {
		return "custom";
	}
}
