package com.example.envgovernance.source;

import com.example.envgovernance.spi.EnvVarSource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.util.Optional;
import java.util.Set;

/**
 * Implementação embutida de {@link EnvVarSource} que representa as variáveis de ambiente
 * do S.O./container ({@code System.getenv()}).
 * <p>
 * O Spring Boot já injeta um {@code systemEnvironment} {@link PropertySource} no ambiente
 * antes de qualquer EPP rodar, portanto {@link #load} retorna {@link Optional#empty()} —
 * nenhuma nova fonte precisa ser inserida. O único trabalho aqui é capturar o snapshot de
 * nomes para os reporters.
 * <p>
 * Ordem {@code 0}: maior prioridade de atribuição. Quando a mesma variável existe nesta
 * fonte e em outra (ex.: Vault), o valor do S.O. vence — consistente com a posição do
 * {@code systemEnvironment} na cadeia de {@link PropertySource}s do Spring.
 *
 * @author Sartre Brasil
 * @since 1.1
 */
public final class SystemEnvVarSource implements EnvVarSource {

	private volatile Set<String> varNames = Set.of();

	@Override
	public String name() {
		return "system-env";
	}

	@Override
	public boolean isAvailable(ConfigurableEnvironment environment) {
		return environment.getProperty("env.governance.sources.system-env.enabled", Boolean.class, true);
	}

	@Override
	public Optional<PropertySource<?>> load(ConfigurableEnvironment environment) {
		// systemEnvironment já está no ambiente — apenas captura os nomes para relatórios
		this.varNames = Set.copyOf(System.getenv().keySet());
		return Optional.empty();
	}

	@Override
	public Set<String> getVarNames() {
		return varNames;
	}

	@Override
	public int getOrder() {
		return 0;
	}
}
