package com.example.envgovernance.vault;

import com.example.envgovernance.spi.EnvVarSource;
import com.example.envgovernance.vault.http.VaultClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link EnvVarSource} que lê segredos do HashiCorp Vault na fase EPP, antes de qualquer
 * bean Spring ser criado.
 *
 * <p>Após um {@code load()} bem-sucedido, injeta um {@link MapPropertySource} no
 * {@link ConfigurableEnvironment} imediatamente após {@code "systemEnvironment"}, fazendo
 * com que os segredos do Vault sejam resolvidos por toda a camada Spring — inclusive pelo
 * {@code RequiredEnvCheckEnvironmentPostProcessor} que valida variáveis obrigatórias.
 *
 * <p>Para múltiplos caminhos ({@code paths}), a primeira ocorrência de uma chave vence
 * ({@code putIfAbsent}): o caminho mais específico deve ser listado primeiro.
 *
 * <p>Todos os segredos do Vault são tratados como sensíveis ({@link #isSensitive} retorna
 * {@code true} para qualquer chave).
 *
 * <h3>Configuração mínima</h3>
 * <pre>{@code
 * env:
 *   governance:
 *     sources:
 *       vault:
 *         address: "${VAULT_ADDR}"
 *         auth:
 *           method: token
 *           token: "${VAULT_TOKEN}"
 *         paths:
 *           - secret/myapp
 * }</pre>
 *
 * @author Sartre Brasil
 * @since 1.1
 * @see VaultConnectionConfig
 * @see VaultClient
 */
public final class VaultEnvVarSource implements EnvVarSource {

	private static final Log log = LogFactory.getLog(VaultEnvVarSource.class);

	private volatile Set<String> varNames = Set.of();
	private volatile String resolvedName = "vault";

	@Override
	public String name() {
		return resolvedName;
	}

	@Override
	public boolean isAvailable(ConfigurableEnvironment environment) {
		// Mínimo necessário: endereço do Vault e ao menos um caminho configurado
		VaultConnectionConfig config = VaultConnectionConfig.from(environment);
		return config.address() != null && !config.address().isBlank()
				&& !config.paths().isEmpty();
	}

	@Override
	public Optional<PropertySource<?>> load(ConfigurableEnvironment environment) {
		VaultConnectionConfig config = VaultConnectionConfig.from(environment);
		VaultClient client = new VaultClient(config);

		Map<String, Object> allSecrets = new LinkedHashMap<>();
		for (String path : config.paths()) {
			log.debug("[ENV GOVERNANCE] Lendo segredos do Vault: " + path);
			Map<String, Object> secrets = client.readSecrets(path);
			secrets.forEach(allSecrets::putIfAbsent); // primeiro caminho vence
		}

		String psName = "vault:" + String.join(",", config.paths());
		this.resolvedName = psName;
		this.varNames = Set.copyOf(allSecrets.keySet());

		log.debug("[ENV GOVERNANCE] Vault carregou " + allSecrets.size()
				+ " segredos de " + config.paths().size() + " caminho(s)");

		return Optional.of(new MapPropertySource(psName, allSecrets));
	}

	@Override
	public Set<String> getVarNames() {
		return varNames;
	}

	/** Todos os segredos provenientes do Vault são sensíveis por definição. */
	@Override
	public boolean isSensitive(String varName) {
		return true;
	}

	@Override
	public int getOrder() {
		return 10;
	}
}
