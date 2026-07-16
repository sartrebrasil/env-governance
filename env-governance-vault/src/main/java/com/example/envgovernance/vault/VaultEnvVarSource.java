package com.example.envgovernance.vault;

import com.example.envgovernance.contract.ConditionalRequirement;
import com.example.envgovernance.contract.Validators;
import com.example.envgovernance.contract.VarSpec;
import com.example.envgovernance.spi.EnvVarSource;
import com.example.envgovernance.vault.http.VaultClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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

	/**
	 * Declara as variáveis de ambiente que o Vault requer quando configurado via convenção
	 * de env vars do S.O. ({@code VAULT_ADDR}, {@code VAULT_AUTH_METHOD}).
	 * <p>
	 * Apenas validação de <em>formato</em> (optional=false): a presença de
	 * {@code VAULT_ADDR} e das credenciais é gerenciada pelos condicionais de
	 * {@link #contributedConditionals()}, pois o Vault é opcional na aplicação.
	 * <p>
	 * Apps que configuram o Vault via propriedades Spring
	 * ({@code env.governance.sources.vault.*}) não são afetadas por estas specs, pois as
	 * chaves Spring não estão registradas aqui — os guards do {@code VaultClient} continuam
	 * atuando como segunda linha de defesa.
	 *
	 * @since 2.2
	 */
	@Override
	public List<VarSpec> contributedSpecs() {
		return List.of(
				new VarSpec("VAULT_ADDR", false, List.of(Validators.url()),
						"Endereço do HashiCorp Vault", false, "url"),
				new VarSpec("VAULT_AUTH_METHOD", false, List.of(Validators.oneOf("token", "approle")),
						"Método de autenticação no Vault (token | approle)", false, "")
		);
	}

	/**
	 * Requisitos condicionais de credenciais quando Vault está configurado via env vars.
	 * <p>
	 * Cada condicional só dispara quando {@code VAULT_ADDR} está presente no ambiente,
	 * evitando falsos positivos em aplicações que não usam o Vault. Os guards equivalentes
	 * no {@link VaultClient} são mantidos como defesa em profundidade.
	 *
	 * @since 2.2
	 */
	@Override
	public List<ConditionalRequirement> contributedConditionals() {
		Predicate<Map<String, String>> vaultEnabled =
				env -> nonBlank(env.get("VAULT_ADDR"));

		Predicate<Map<String, String>> isApprole =
				env -> "approle".equalsIgnoreCase(env.getOrDefault("VAULT_AUTH_METHOD", ""));

		// Token é o padrão: dispara quando Vault está habilitado e auth ≠ approle
		Predicate<Map<String, String>> isToken =
				env -> vaultEnabled.test(env)
						&& !"approle".equalsIgnoreCase(env.getOrDefault("VAULT_AUTH_METHOD", ""));

		return List.of(
				new ConditionalRequirement(
						env -> vaultEnabled.test(env) && isApprole.test(env),
						List.of("VAULT_ROLE_ID", "VAULT_SECRET_ID"),
						"Vault AppRole: VAULT_ROLE_ID e VAULT_SECRET_ID obrigatórios quando VAULT_AUTH_METHOD=approle"),
				new ConditionalRequirement(
						isToken,
						List.of("VAULT_TOKEN"),
						"Vault token: VAULT_TOKEN obrigatório quando VAULT_ADDR configurado (método padrão ou explícito)")
		);
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

	private static boolean nonBlank(String s) {
		return s != null && !s.isBlank();
	}
}
