package com.example.envgovernance.contract;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Requisito condicional (cross-field): quando a condição é satisfeita pelo ambiente,
 * o conjunto {@link #requiredWhenTrue()} de variáveis passa a ser obrigatório.
 * <p>
 * Generaliza regras antes embutidas em fontes específicas — por exemplo,
 * "se {@code VAULT_AUTH_METHOD=approle}, então {@code VAULT_ROLE_ID} e
 * {@code VAULT_SECRET_ID} são obrigatórias".
 *
 * @param condition       predicado avaliado sobre o mapa plano de ambiente já resolvido
 * @param requiredWhenTrue variáveis exigidas quando {@code condition} retorna {@code true}
 * @param description     texto legível da condição, usado em mensagens (ex.: {@code "AUTH_METHOD=approle"})
 * @author Sartre Brasil
 * @since 2.2
 */
public record ConditionalRequirement(
		Predicate<Map<String, String>> condition,
		List<String> requiredWhenTrue,
		String description) {

	public ConditionalRequirement {
		if (condition == null) {
			throw new IllegalArgumentException("ConditionalRequirement.condition não pode ser nulo");
		}
		requiredWhenTrue = requiredWhenTrue == null ? List.of() : List.copyOf(requiredWhenTrue);
		description = description == null ? "" : description;
	}

	/**
	 * Constrói um requisito cuja condição é a igualdade de uma variável a um valor esperado.
	 *
	 * @param key             nome da variável a comparar
	 * @param expectedValue   valor que dispara o requisito
	 * @param requiredWhenTrue variáveis obrigatórias quando {@code key == expectedValue}
	 */
	public static ConditionalRequirement whenEquals(String key, String expectedValue, String... requiredWhenTrue) {
		Predicate<Map<String, String>> predicate = env -> expectedValue.equals(env.get(key));
		return new ConditionalRequirement(predicate, List.of(requiredWhenTrue), key + "=" + expectedValue);
	}
}
