package com.example.envgovernance.vault;

import com.example.envgovernance.spi.EnvVarSource;
import com.example.envgovernance.vault.http.VaultClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@link EnvVarSource} que lê segredos do HashiCorp Vault, sem dependências de framework.
 * <p>
 * Para múltiplos caminhos ({@code paths}), a primeira ocorrência de uma chave vence
 * ({@code putIfAbsent}): o caminho mais específico deve ser listado primeiro.
 * <p>
 * Todos os segredos do Vault são tratados como sensíveis ({@link #isSensitive} retorna
 * {@code true} para qualquer chave).
 *
 * <h3>Configuração mínima (Spring Boot)</h3>
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
 * <h3>Configuração mínima (não-Spring)</h3>
 * <pre>{@code
 * VAULT_ADDR=https://vault.example.com
 * VAULT_TOKEN=hvs.xxx
 * VAULT_PATHS=secret/myapp
 * }</pre>
 *
 * @author Sartre Brasil
 * @since 1.1
 * @see VaultConnectionConfig
 * @see VaultClient
 */
public final class VaultEnvVarSource implements EnvVarSource {

	private static final Logger log = Logger.getLogger(VaultEnvVarSource.class.getName());

	private volatile Set<String> varNames = Set.of();
	private volatile String resolvedName = "vault";

	@Override
	public String name() {
		return resolvedName;
	}

	@Override
	public boolean isAvailable(Map<String, String> environment) {
		VaultConnectionConfig config = VaultConnectionConfig.from(environment);
		return config.address() != null && !config.address().isBlank()
				&& !config.paths().isEmpty();
	}

	@Override
	public Map<String, String> load(Map<String, String> environment) {
		VaultConnectionConfig config = VaultConnectionConfig.from(environment);
		VaultClient client = new VaultClient(config);

		Map<String, String> allSecrets = new LinkedHashMap<>();
		for (String path : config.paths()) {
			log.fine("[ENV GOVERNANCE] Lendo segredos do Vault: " + path);
			Map<String, String> secrets = client.readSecrets(path);
			secrets.forEach(allSecrets::putIfAbsent); // primeiro caminho vence
		}

		this.resolvedName = "vault:" + String.join(",", config.paths());
		this.varNames = Set.copyOf(allSecrets.keySet());

		log.fine("[ENV GOVERNANCE] Vault carregou " + allSecrets.size()
				+ " segredos de " + config.paths().size() + " caminho(s)");

		return Map.copyOf(allSecrets);
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
