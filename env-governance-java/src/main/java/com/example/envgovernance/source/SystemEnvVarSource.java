package com.example.envgovernance.source;

import com.example.envgovernance.spi.EnvVarSource;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Implementação embutida de {@link EnvVarSource} que representa as variáveis de ambiente
 * do S.O./container ({@code System.getenv()}).
 * <p>
 * {@link #load} retorna mapa vazio — o ambiente base já contém as vars do S.O. O único
 * trabalho aqui é capturar o snapshot de nomes para os reporters de atribuição.
 * <p>
 * Ordem {@code 0}: maior prioridade de atribuição. Quando a mesma variável existe nesta
 * fonte e em outra (ex.: Vault), o valor do S.O. vence.
 *
 * @author Sartre Brasil
 * @since 2.0
 */
public final class SystemEnvVarSource implements EnvVarSource {

	private volatile Set<String> varNames = Set.of();

	@Override
	public String name() {
		return "system-env";
	}

	@Override
	public boolean isAvailable(Map<String, String> environment) {
		String value = environment.get("env.governance.sources.system-env.enabled");
		return value == null || Boolean.parseBoolean(value);
	}

	@Override
	public Map<String, String> load(Map<String, String> environment) {
		this.varNames = Set.copyOf(System.getenv().keySet());
		return Collections.emptyMap();
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
