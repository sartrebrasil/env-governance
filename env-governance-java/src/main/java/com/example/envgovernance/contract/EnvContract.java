package com.example.envgovernance.contract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Contrato de configuração: o conjunto imutável de {@link VarSpec}s e
 * {@link ConditionalRequirement}s que uma aplicação declara esperar do ambiente.
 * <p>
 * É o modelo comum para o qual convergem a API fluente (apps não-Spring), o arquivo
 * declarativo e as contribuições de fontes ({@code EnvVarSource}). A avaliação
 * ({@link #validate(Map)}) é pura — recebe um mapa plano de ambiente já resolvido e
 * devolve as violações, sem efeitos colaterais nem I/O — de modo que os caminhos Spring
 * e não-Spring compartilham exatamente a mesma lógica.
 *
 * @author Sartre Brasil
 * @since 2.2
 */
public final class EnvContract {

	private static final EnvContract EMPTY = new EnvContract(List.of(), List.of());

	private final List<VarSpec> specs;
	private final List<ConditionalRequirement> conditionals;

	public EnvContract(List<VarSpec> specs, List<ConditionalRequirement> conditionals) {
		this.specs = List.copyOf(specs);
		this.conditionals = List.copyOf(conditionals);
	}

	/** Contrato vazio — {@link #validate(Map)} nunca produz violações. */
	public static EnvContract empty() {
		return EMPTY;
	}

	public List<VarSpec> specs() {
		return specs;
	}

	public List<ConditionalRequirement> conditionals() {
		return conditionals;
	}

	public boolean isEmpty() {
		return specs.isEmpty() && conditionals.isEmpty();
	}

	/** Retorna a spec da variável de nome informado, se declarada. */
	public Optional<VarSpec> specFor(String name) {
		return specs.stream().filter(s -> s.name().equals(name)).findFirst();
	}

	/**
	 * Combina este contrato com {@code other}. Em caso de {@link VarSpec}s com o mesmo nome,
	 * <strong>este contrato tem precedência</strong>; as {@link ConditionalRequirement}s de
	 * ambos são concatenadas. O chamador controla a precedência pela ordem da chamada.
	 */
	public EnvContract merge(EnvContract other) {
		if (other == null || other.isEmpty()) {
			return this;
		}
		if (this.isEmpty()) {
			return other;
		}
		Map<String, VarSpec> byName = new LinkedHashMap<>();
		for (VarSpec spec : other.specs) {
			byName.put(spec.name(), spec);
		}
		for (VarSpec spec : this.specs) {
			byName.put(spec.name(), spec); // este vence
		}
		List<ConditionalRequirement> mergedConditionals = new ArrayList<>(this.conditionals);
		mergedConditionals.addAll(other.conditionals);
		return new EnvContract(List.copyOf(byName.values()), mergedConditionals);
	}

	/**
	 * Nomes efetivamente obrigatórios para o ambiente informado: as specs marcadas como
	 * {@code required} mais as variáveis promovidas por {@link ConditionalRequirement}s
	 * cuja condição é satisfeita.
	 */
	public Set<String> effectiveRequiredNames(Map<String, String> environment) {
		Set<String> required = new TreeSet<>();
		for (VarSpec spec : specs) {
			if (spec.required()) {
				required.add(spec.name());
			}
		}
		for (ConditionalRequirement conditional : conditionals) {
			if (conditional.condition().test(environment)) {
				required.addAll(conditional.requiredWhenTrue());
			}
		}
		return required;
	}

	/**
	 * Avalia o contrato contra o ambiente resolvido e devolve todas as violações,
	 * ordenadas por nome e categoria (determinístico).
	 * <ul>
	 *   <li>{@link ContractViolation.Kind#MISSING} — spec obrigatória ausente.</li>
	 *   <li>{@link ContractViolation.Kind#CONDITIONAL_MISSING} — exigida por condição satisfeita, ausente.</li>
	 *   <li>{@link ContractViolation.Kind#INVALID} — presente, mas reprovada por um {@link ValueValidator}.</li>
	 * </ul>
	 */
	public List<ContractViolation> validate(Map<String, String> environment) {
		List<ContractViolation> violations = new ArrayList<>();

		Set<String> staticRequired = new TreeSet<>();
		for (VarSpec spec : specs) {
			if (spec.required()) {
				staticRequired.add(spec.name());
			}
		}
		for (String name : staticRequired) {
			if (!environment.containsKey(name)) {
				violations.add(new ContractViolation(name, ContractViolation.Kind.MISSING,
						name + ": variável obrigatória ausente", null));
			}
		}

		Set<String> conditionalRequired = new TreeSet<>();
		for (ConditionalRequirement conditional : conditionals) {
			if (conditional.condition().test(environment)) {
				conditionalRequired.addAll(conditional.requiredWhenTrue());
			}
		}
		for (String name : conditionalRequired) {
			if (staticRequired.contains(name)) {
				continue; // já reportada como MISSING
			}
			if (!environment.containsKey(name)) {
				violations.add(new ContractViolation(name, ContractViolation.Kind.CONDITIONAL_MISSING,
						name + ": obrigatória pela condição satisfeita, mas ausente", null));
			}
		}

		for (VarSpec spec : specs) {
			String value = environment.get(spec.name());
			if (value == null) {
				continue; // ausência já tratada acima; validadores só rodam se presente
			}
			for (ValueValidator validator : spec.validators()) {
				ValidationResult result = validator.validate(spec.name(), value);
				if (!result.valid()) {
					violations.add(new ContractViolation(spec.name(), ContractViolation.Kind.INVALID,
							result.message(), value));
				}
			}
		}

		violations.sort(Comparator.comparing(ContractViolation::name)
				.thenComparing(v -> v.kind().name()));
		return List.copyOf(violations);
	}
}
