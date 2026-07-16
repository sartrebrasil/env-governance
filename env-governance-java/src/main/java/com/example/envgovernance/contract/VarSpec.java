package com.example.envgovernance.contract;

import java.util.List;

/**
 * Especificação de contrato de uma única variável de ambiente.
 * <p>
 * Declara se a variável é obrigatória, quais restrições de valor se aplicam e metadados
 * usados em relatórios e mascaramento. Instâncias são produzidas tanto pela API fluente
 * do {@code GovernanceContext} quanto por um contrato declarativo carregado de arquivo —
 * ambos convergem para este mesmo modelo.
 *
 * @param name        nome da variável de ambiente (ex.: {@code "DB_URL"})
 * @param required    {@code true} se a ausência é uma falha de configuração
 * @param validators  restrições de valor aplicadas quando a variável está presente
 * @param description descrição legível (pode ser vazia)
 * @param sensitive   {@code true} para mascarar o valor em relatórios
 * @param type        rótulo de tipo para exibição (ex.: {@code "port"}, {@code "url"}); pode ser vazio
 * @author Sartre Brasil
 * @since 2.2
 */
public record VarSpec(
		String name,
		boolean required,
		List<ValueValidator> validators,
		String description,
		boolean sensitive,
		String type) {

	public VarSpec {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("VarSpec.name não pode ser nulo ou vazio");
		}
		validators = validators == null ? List.of() : List.copyOf(validators);
		description = description == null ? "" : description;
		type = type == null ? "" : type;
	}

	/** Cria uma spec mínima apenas com nome e obrigatoriedade, sem validadores. */
	public static VarSpec of(String name, boolean required) {
		return new VarSpec(name, required, List.of(), "", false, "");
	}
}
