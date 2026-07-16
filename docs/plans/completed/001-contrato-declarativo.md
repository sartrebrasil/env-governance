# Plano de implementação — ADR-001: Contrato declarativo com validação de valor

- **ADR**: [001-contrato-declarativo-com-validacao-de-valor](../../adr/001-contrato-declarativo-com-validacao-de-valor.md)
- **Status**: ✅ Concluído
- **Data**: 2026-07-14
- **Concluído em**: 2026-07-14

Escopo: itens 1–3 da análise de gaps — validação de valor, requisitos condicionais e contrato declarativo explícito. Enforcement em CI (item 4) é decisão/plano separado que depende deste.

Princípio inegociável: **opcional e aditivo**. Sem contrato, o comportamento é byte-for-byte o atual (só presença). Núcleo `env-governance-java` continua **zero-dep** (JDK 21).

---

## Pontos de integração no código atual

- `env-governance-java` é de fato zero-dep (só `spring-boot-starter-test` em `test`) — **não há parser YAML no classpath de runtime do núcleo**. É o maior driver de design do arquivo declarativo (ver Questões abertas #2).
- Merge/missing não-Spring: `GovernanceContext.check()` — merge em `GovernanceContext.java:71-81`, cálculo de ausentes `:83-86`, `GovernanceResult` montado `:88`. `verify()` `:95-107`.
- `GovernanceResult` é record de 3 componentes (`GovernanceResult.java:15-22`); `hasGaps()` `:20` só olha `missingRequired`.
- `MissingRequiredEnvironmentVariablesException` carrega só `List<String>` (`:17-27`).
- SPI `EnvVarSource` já usa `default` extensível (`isSensitive` `:66-71`) — molde para adicionar contribuição de contrato sem quebrar implementadores.
- Pipeline Spring (ordens): `DeclaredVarsScanner` **+15** (`:118-120`) → popula `DeclaredVarsRegistry`; `EnvVarSourceLoaderPostProcessor` **+18** (`:119-122`), `flatEnv` em `:64`; `RequiredEnvCheckEnvironmentPostProcessor` **+20** (`:97-100`), filtro de presença `:65-69`.
- `DeclaredVar` record `DeclaredVarsRegistry.java:37-43`, merge `:53-60`.
- Reporter: categorias em `EnvVarUsageReporter.logInfo` `:84-184`, `hasGaps` `:132`, blocos `:143-162`.
- Endpoint: JSON em `EnvGovernanceEndpoint.report()` `:131-137`, listas de gaps `:59-80`.
- Properties: `EnvGovernanceProperties` `:36-45`.
- **Lógica condicional ad-hoc do Vault** está no `VaultClient` (não no `VaultEnvVarSource`): `requireToken()` `:89-95` e `loginWithAppRole()` `:97-103` lançam `VaultClientException` no connect se faltar `VAULT_TOKEN`/`VAULT_ROLE_ID`/`VAULT_SECRET_ID`. `VaultConnectionConfig.from()` `:52-102` lê `VAULT_AUTH_METHOD` com fallback chave-Spring→var-OS; `AuthMethod.parse` `:39-45`.

---

## 1. Novos tipos

Modelo + engine no módulo zero-dep `env-governance-java`, pacote novo `com.example.envgovernance.contract`.

| Tipo | Módulo | Responsabilidade |
|---|---|---|
| `EnvContract` | java | Agregado imutável: `List<VarSpec>` + `List<ConditionalRequirement>`; `specFor(name)`, `merge(EnvContract)` (combina programático + declarativo + contribuído por SPI). |
| `VarSpec` (record) | java | Contrato de uma var: `name`, `required`, `List<ValueValidator> validators`, `description`, `sensitive`, `type` (rótulo p/ relatório/enum). |
| `ConditionalRequirement` (record) | java | `Predicate<Map<String,String>> condition` + `List<String> requiredWhenTrue` + `description` legível (ex.: `"VAULT_AUTH_METHOD=approle"`). |
| `ValueValidator` (interface) | java | `ValidationResult validate(String name, String value)` (funcional) + `String describe()`. |
| `ValidationResult` (record) | java | `boolean valid`, `String message`; estáticos `ok()`/`invalid(msg)`. |
| `Validators` (factory) | java | Built-ins JDK puro: `integer()`, `port()` (1-65535), `url()` (via `java.net.URI`), `bool()`, `nonEmpty()`, `oneOf(String...)`, `regex(String)`, `min(long)`, `max(long)`. |
| `ContractViolation` (record) | java | `name`, `kind` (`MISSING`/`INVALID`/`CONDITIONAL_MISSING`), `message`, `value` opcional. Alimenta o gap `[INVALID]`. |
| `EnvContractLoader` | java | Carrega contrato declarativo de `InputStream`/classpath → `EnvContract` (ver §4). |
| `ContractRegistry` | core | Registry estático (espelha `DeclaredVarsRegistry`/`EnvVarSourceRegistry`): `EnvContract` merged + `List<ContractViolation>` p/ o lado Spring, com `reset()`. |
| `EnvContractScanner` (EPP) | core | Carrega `env-governance.yml` (Spring) + coleta contratos contribuídos por SPI, faz merge no `ContractRegistry`. |
| `EnvContractValidationEnvironmentPostProcessor` (EPP) | core | Roda validadores + condicionais contra o ambiente resolvido; lança em falha. |

**Requisito condicional** = `Predicate<Map<String,String>>` sobre o mapa plano já mergeado. Funciona idêntico nos dois caminhos porque ambos produzem `Map<String,String>` (`check()` `:71-81`; `flatEnv` em `EnvVarSourceLoaderPostProcessor.java:64`). O fluente `requireIf("AUTH_METHOD=approle", "ROLE_ID","SECRET_ID")` compila a string `"KEY=value"` num predicado de igualdade; overload `requireIf(Predicate, String...)` cobre casos complexos.

---

## 2. API fluente no `GovernanceContext.Builder`

**Decisão central:** `require(String)` hoje retorna `Builder` (`GovernanceContext.java:136-139`). Validadores (`.asPort()`, `.oneOf(...)`) são **por-variável** → precisam de um **sub-builder** escopado à última var.

Abordagem recomendada — `VarSpecBuilder` aninhado que delega ao `Builder` pai:
- `require(String)` e novo `optional(String)` retornam `VarSpecBuilder` (guarda ref ao pai + `VarSpec` em construção).
- `VarSpecBuilder` expõe os validadores (`asInt()`, `asPort()`, `asUrl()`, `asBoolean()`, `nonEmpty()`, `oneOf(...)`, `matches(regex)`, `min`, `max`, `describedAs(...)`, `sensitive()`) — cada um muta o spec e retorna `this`.
- `VarSpecBuilder` **re-expõe todos os métodos terminais do pai** (`require`, `optional`, `addSource`, `fromDotEnv`, `fromProperties`, `withoutServiceLoaderDiscovery`, `requireIf`, `build`), finalizando o spec atual no pai — mantém cadeias existentes fonte-compatíveis.

**Classificação de compatibilidade:** mudar o retorno de `require` de `Builder`→`VarSpecBuilder` é **fonte-compatível** mas **binário-incompatível** (muda tipo de retorno). Duas formas:
- **Opção A (recomendada — casa com o ADR):** `require`→`VarSpecBuilder`; documentar como quebra binária que exige recompilar (aceitável; não há downstream compilado de longa vida neste repo). Permite exatamente `.require("PORT").asPort()`.
- **Opção B (estritamente aditiva/binário-safe):** manter `require→Builder`; adicionar `declare(String)→VarSpecBuilder`. Perde a grafia do ADR.

Novos campos no `Builder` (junto a `:128-131`): `List<VarSpec> specs`, `List<ConditionalRequirement> conditionals`. `build()` (`:184-195`) monta um `EnvContract` e passa ao construtor (`:54-60`). Método `fromContract(InputStream/Path)` carrega arquivo declarativo e faz merge. Programático e declarativo produzem o **mesmo** `EnvContract` — um modelo, dois front-ends.

---

## 3. Aplicação no caminho não-Spring (`check()`)

Mudanças em `GovernanceContext.check()` (`:69-89`), inseridas **após** o merge (depois de `:81`) e dobradas no cálculo de ausentes (`:83-86`):
1. **Condicionais** — avaliar cada `condition` contra `merged`; nomes em `requiredWhenTrue` entram no conjunto obrigatório antes do filtro de presença. Subsume o `requiredVarNames` estático atual.
2. **Presença** — lógica inalterada (specs `required` + nomes promovidos por condicional ausentes de `merged`).
3. **Validação de valor** — para cada `VarSpec` **presente** em `merged`, rodar `validators`; coletar `ContractViolation(kind=INVALID)`.
4. Montar resultado com a lista de violations.

**`GovernanceResult` (quebra vs aditivo):** adicionar 4º componente `List<ContractViolation> violations` muda o construtor canônico — **binário-quebrante para chamador externo do construtor** (único chamador é interno, `:88`). Mitigação: adicionar o componente **e** um construtor de conveniência de 3 args com `violations = List.of()` (fonte-compat). `hasGaps()` (`:20-22`) vira `!missingRequired.isEmpty() || !violations.isEmpty()`. **Comportamento retroativo byte-for-byte:** sem contrato, zero specs/condicionais → `violations` sempre vazio → `hasGaps()` reduz à expressão antiga.

**`verify()` + exceção:** `verify()` (`:95-107`) deve falhar também em `[INVALID]`/condicional. Recomendado: manter `MissingRequiredEnvironmentVariablesException` para puro-ausente e adicionar irmã `EnvContractValidationException extends RuntimeException` com `List<ContractViolation>`; `verify()` lança a antiga quando só presença falha (mensagem/comportamento inalterados) e a nova quando validações falham. Ver Questão aberta #3.

---

## 4. Carregamento do `env-governance.yml` nos dois caminhos

Restrição dura: **o módulo zero-dep não tem parser YAML.**
- **Spring (`core`):** depende de Spring Boot → SnakeYAML + `YamlPropertySourceLoader` disponíveis. `EnvContractScanner` carrega `classpath:env-governance.yml` via `YamlPropertySourceLoader` → props planas → `EnvContract`. Sem nova dependência. Classpath via padrão de `EnvVarSourceLoaderPostProcessor.java:55-57`.
- **Não-Spring (`java`):** não pode usar SnakeYAML. Recomendação combinada:
  - **(4a) Formato dual:** contrato flat `.properties` (`env-governance.properties`) via `EnvFileReader`/`java.util.Properties` (`EnvFileReader.java:54-64`) no caminho zero-dep; YAML completo só sob Spring. Chaves mapeiam a namespace plano (`PORT.required=true`, `PORT.type=port`, `MODE.oneOf=a,b`, `requireIf.approle.when=AUTH_METHOD=approle`, `requireIf.approle.require=ROLE_ID,SECRET_ID`).
  - **(4c) SPI de parser plugável:** `EnvContractParser` via `ServiceLoader`; impl YAML no `core`, impl properties no `java`. Discovery no java via `Thread.currentThread().getContextClassLoader().getResourceAsStream(...)`.

Tensão "arquivo único" para decisão humana (#2).

---

## 5. Integração no pipeline Spring (validação + `[INVALID]`)

Novos EPPs e ordem (vs +15/+18/+20 existentes):
- `EnvContractScanner` em **+16** — após `DeclaredVarsScanner`; carrega `env-governance.yml` + junta contratos de SPI (§6), faz merge no `ContractRegistry`.
- `EnvContractValidationEnvironmentPostProcessor` em **+21** — após `RequiredEnvCheck` (+20) e `EnvVarSourceLoader` (+18, p/ ter valores do Vault resolvidos). Lê valores resolvidos do `environment` (mesmo `containsProperty`/`getProperty` de `RequiredEnvCheck.java:67`), roda validadores + condicionais, grava `ContractViolation`s no `ContractRegistry`, lança `EnvContractValidationException` quando `env.governance.contract.fail-on-invalid=true`.

**Por que não dobrar no +20:** presença condicional-obrigatória deve abortar no mesmo estágio das ausentes normais → estender `RequiredEnvCheck` (`:65-69`) para unir os nomes required + condicional-required do `ContractRegistry` (aditivo: contrato vazio = sem mudança). O check de valor/`INVALID` fica separado no +21 (categoria distinta, `fail-on-invalid` togglável independente de `fail-on-missing`).

**Reporter (`EnvVarUsageReporter`):** bloco `[INVALID]` a partir de `ContractRegistry.getViolations()` — computar lista perto de `:90-110`, somar ao `hasGaps` (`:132`), bloco `log.error("[INVALID] ...")` espelhando `:143-148`; opcional sub-nota `[CONDITIONAL]`.

**Endpoint (`EnvGovernanceEndpoint`):** adicionar `invalid` ao mapa `gaps` (`:131-137`): `gaps: {required, fallback, noValue, invalid}`, cada item `{name, value(mascarado se sensível), reason}`, construído do `ContractRegistry` como `:59-80`. Máscara via `isSensitive` (`EnvVarSource.java:66-71`) ou `VarSpec.sensitive`.

---

## 6. Refatoração do condicional do Vault

Novo método SPI opcional em `EnvVarSource` (default aditivo, espelha `isSensitive` `:66-71`):
```java
default List<VarSpec> contributedSpecs() { return List.of(); }
default List<ConditionalRequirement> contributedConditionals() { return List.of(); }
```
Como `VarSpec`/`ConditionalRequirement` moram no módulo zero-dep, o SPI segue zero-dep; implementadores existentes intactos (default vazio).

`VaultEnvVarSource` implementa, genericamente:
- `VAULT_ADDR` required + `nonEmpty()`/`url()`; `VAULT_PATHS` required.
- `requireIf(VAULT_AUTH_METHOD == "approle" → VAULT_ROLE_ID, VAULT_SECRET_ID)`.
- `requireIf(VAULT_AUTH_METHOD == "token"/ausente → VAULT_TOKEN)`.
- `VAULT_AUTH_METHOD oneOf("token","approle")`.

Deve respeitar o fallback de dois níveis de `VaultConnectionConfig.from()` (`:52-102`) — o predicado checa `env.governance.sources.vault.auth.method` **e** `VAULT_AUTH_METHOD`; reusar `VaultConnectionConfig.from(env).authMethod()` em vez de compare cru (parsing num lugar só, `AuthMethod.parse` `:39-45`).

`EnvContractScanner` (+16) chama `contributedSpecs()`/`contributedConditionals()` em cada fonte descoberta por `ServiceLoader`; no não-Spring, `GovernanceContext.build()` (`:184-195`) faz o mesmo. As guardas em `VaultClient` (`:89-103`) **permanecem como defesa em profundidade** (protegem uso direto do `VaultClient`), mas deixam de ser o enforcement primário — o startup agora falha antes, no +20/+21, com relatório unificado.

---

## 7. Novos campos em `EnvGovernanceProperties`

Aditivo (record `@ConfigurationProperties` tolera novos componentes) — preferir sub-record `contract` (namespace `env.governance.contract.*`, consistente com `env.governance.sources.*`):
- `contract.enabled` (default `true`)
- `contract.location` (default `classpath:env-governance.yml`)
- `contract.fail-on-invalid` (default `true`)
- `contract.strict-unknown-vars` (default `false`) — reservado p/ futuro gap "var presente fora do contrato"; off por default preserva comportamento.

---

## 8. Sequenciamento em PRs (cada um testável isoladamente)

| PR | Conteúdo | Natureza | Depende de | Status |
|---|---|---|---|---|
| **PR-1** | Pacote `contract`: modelo + engine de validação (java). Só biblioteca + testes; nada wired. | aditivo | — | ✅ Concluído |
| **PR-2** | Wiring não-Spring: `VarSpecBuilder`, `optional`, `requireIf`; aplicação em `check()`; `GovernanceResult.violations`; `EnvContractValidationException`; `verify()`. | aditivo (comportamento); **binário-quebra** em `require`/`GovernanceResult` | PR-1 | ✅ Concluído |
| **PR-3** | `EnvContractLoader` + SPI de parser; parser properties no java; discovery classpath. | aditivo | PR-1 | ✅ Concluído |
| **PR-4** | Spring: `ContractRegistry`, `EnvContractScanner` (+16, registrar em `spring.factories`), `EnvContractValidationEnvironmentPostProcessor` (+21), impl YAML do parser, estender `RequiredEnvCheck` (+20). | aditivo (contrato vazio = sem mudança) | PR-1, PR-3 | ✅ Concluído |
| **PR-5** | SPI `contributedSpecs`/`contributedConditionals`; wiring no scanner + `build()`; `[INVALID]` no reporter e endpoint; campos de `EnvGovernanceProperties`. | aditivo | PR-4 | ✅ Concluído |
| **PR-6** | Refatoração Vault sobre o mecanismo geral; guardas do `VaultClient` mantidas. | aditivo | PR-5 | ✅ Concluído |

Cadeia: PR-1 → (PR-2 ‖ PR-3) → PR-4 → PR-5 → PR-6.

### Decisões tomadas durante a implementação

| # | Questão | Decisão tomada |
|---|---|---|
| 1 | Quebra binária em `require()` | **Opção A** — retorno `VarSpecBuilder`; `flush()` re-expõe todos os terminais do pai (fonte-compat). |
| 2 | Arquivo único vs formato dual | **SPI de parser (4c)** — `EnvContractParser` via `ServiceLoader`; `.properties` no zero-dep, YAML no core (com SnakeYAML `<optional>`). |
| 3 | Taxonomia de exceção | **Tipo novo** — `EnvContractValidationException` separada de `MissingRequiredEnvironmentVariablesException`. |
| 4 | 4º componente de `GovernanceResult` | Adicionado; construtor 3-args de conveniência mantém compat de fonte. |
| 6 | Fallback dois-níveis no Vault | `contributedSpecs()`/`contributedConditionals()` são keyed por var OS (`VAULT_ADDR` etc.); Spring property form não é afetada — guards do `VaultClient` agem como defesa em profundidade. |

---

## 9. Plano de testes por PR

- **PR-1:** unit de cada validador (limites de porta, `oneOf`, `url` via `URI`, `min`/`max`, `regex`, `nonEmpty`, boolean); precedência de `EnvContract.merge`; avaliação de `ConditionalRequirement`. Sem Mockito. ~80% no pacote novo.
- **PR-2:** `GovernanceContextTest` — (a) caminho sem-contrato idêntico ao atual (lock de regressão: `hasGaps`, `missingRequired`, `resolvedVars`, `attributions`); (b) presente-mas-inválido → violation `INVALID`, `verify()` lança `EnvContractValidationException`; (c) `requireIf` promove var a ausente; (d) cadeia `VarSpecBuilder` compila/comporta. Testes existentes verdes (prova de fonte-compat).
- **PR-3:** round-trip do loader (properties → `EnvContract`); input malformado com exceção **específica** (nunca `catch(Exception)`).
- **PR-4:** testes de EPP no padrão de `RequiredEnvCheckEnvironmentPostProcessorTest` (mock `ConfigurableEnvironment`), `ContractRegistry.reset()` entre testes (espelha `DeclaredVarsRegistry.reset()`); verificar ordens (+16, +21); verificar contrato vazio = comportamento atual byte-for-byte.
- **PR-5:** reporter render `[INVALID]` + `hasGaps` vira true; endpoint shape `gaps.invalid` + máscara de sensível; binding de `@ConfigurationProperties` dos novos campos.
- **PR-6:** Vault (Mockito no `VaultClient`/HTTP como em `VaultClientTest`): `AUTH_METHOD=approle` sem `ROLE_ID`/`SECRET_ID` vira violation em scan/validation, não `VaultClientException`; fallback de dois níveis honrado.

---

## 10. Questões abertas (decisão humana)

1. ~~**Quebra binária em `require()` (§2).** Opção A vs Opção B.~~ ✅ **Resolvida** — Opção A implementada.
2. ~~**Arquivo único vs formato dual (§4).**~~ ✅ **Resolvida** — SPI de parser (4c): `.properties` no zero-dep, YAML no core.
3. ~~**Taxonomia de exceção (§3).**~~ ✅ **Resolvida** — `EnvContractValidationException` como tipo novo.
4. ~~**4º componente de `GovernanceResult`.**~~ ✅ **Resolvida** — adicionado; construtor 3-args de compat de fonte.
5. **Reconciliação "duas fontes de verdade" (ADR).** Quando `env-governance.yml` nomeia var que `DeclaredVarsScanner` não descobriu (ou vice-versa), o ADR quer gap reportado, não silêncio. Falta regra de precedência entre `ContractRegistry` e `DeclaredVarsRegistry`. ⚠️ **Pendente** — escopo futuro.
6. **Valores não resolvíveis em tempo de EPP.** Fonte com `on-failure=warn/skip` (`EnvVarSourceLoaderPostProcessor.java:95-105`) deixa vars ausentes — decidir se vira `MISSING` ou é suprimido p/ evitar `INVALID` falso. ⚠️ **Pendente** — escopo futuro.
7. **Default de `strict-unknown-vars`.** Off preserva comportamento; confirmar se o objetivo "comparar ambientes" quer on em perfis de CI. ⚠️ **Pendente** — campo reservado; feature não implementada.
