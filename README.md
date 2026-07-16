# env-governance

Biblioteca de governança de variáveis de ambiente para aplicações Java. Detecta gaps de configuração no startup — variáveis obrigatórias ausentes, placeholders sem valor, sobrescritas ativas do SO, **valores inválidos** e **requisitos condicionais** — e suporta fontes externas de segredos como o HashiCorp Vault.

Além de verificar **presença**, a lib valida **conteúdo** a partir de um contrato declarativo opcional (`env-governance.yml`/`.properties`) ou de uma API fluente — tipos (`int`, `port`, `url`, `boolean`), conjuntos permitidos (`oneOf`), faixas (`min`/`max`), regex e regras cross-field (ex.: "se `AUTH_METHOD=approle`, então `ROLE_ID` e `SECRET_ID` são obrigatórias").

Funciona em dois contextos:
- **Spring Boot 3.x** — integração automática via `EnvironmentPostProcessor` e Actuator.
- **Qualquer aplicação Java** — API standalone sem dependências de framework.

[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F)](https://spring.io/projects/spring-boot)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00)](https://openjdk.org/projects/jdk/21/)

---

## Índice

- [O problema que resolve](#o-problema-que-resolve)
- [Módulos: qual dependência usar](#módulos-qual-dependência-usar)
- [Instalação e uso básico](#instalação-e-uso-básico)
  - [Apps Spring Boot](#apps-spring-boot)
  - [Apps Spring Boot com Vault](#apps-spring-boot-com-vault)
  - [Apps não-Spring](#apps-não-spring)
  - [Apps não-Spring com Vault](#apps-não-spring-com-vault)
- [Como funciona](#como-funciona)
  - [Spring Boot — pipeline de inicialização](#spring-boot--pipeline-de-inicialização)
  - [Apps não-Spring — GovernanceContext](#apps-não-spring--governancecontext)
- [Contrato declarativo de configuração](#contrato-declarativo-de-configuração)
  - [Modo 1: API fluente (não-Spring)](#modo-1-api-fluente-não-spring)
  - [Modo 2: arquivo declarativo](#modo-2-arquivo-declarativo)
  - [Atributos de uma variável](#atributos-de-uma-variável)
  - [Requisitos condicionais](#requisitos-condicionais)
  - [Validadores embutidos](#validadores-embutidos)
  - [Contribuições do Vault](#contribuições-do-vault)
- [Configuração](#configuração)
  - [Propriedades Spring Boot](#propriedades-spring-boot)
  - [Configuração do Vault](#configuração-do-vault)
- [Interpretando os logs](#interpretando-os-logs)
- [Endpoint Actuator](#endpoint-actuator)
- [Implementando uma fonte customizada (SPI)](#implementando-uma-fonte-customizada-spi)
- [Referência de gaps](#referência-de-gaps)

---

## O problema que resolve

Em containers e ambientes cloud, propriedades do Spring Boot podem ser sobrescritas por variáveis de ambiente a qualquer momento. Descobrir que uma variável está faltando, errada ou sendo ignorada costuma acontecer em produção, quando o custo é alto.

O **env-governance** roda no startup, cruza as propriedades declaradas com o ambiente do SO/container — e com fontes externas de segredos — e reporta exatamente o que está mal configurado, antes de qualquer bean ser criado.

```
===== ENV GOVERNANCE: VARIÁVEIS OBRIGATÓRIAS AUSENTES (2) =====
  DB_PASSWORD                                    → spring.datasource.password  [application.yml]
  API_KEY                                        → integration.api.key         [application.yml]
=================================================================
Defina as variáveis acima no ambiente antes de iniciar a aplicação.
```

---

## Módulos: qual dependência usar

O projeto é dividido em módulos com escopos bem definidos. Escolha conforme o seu contexto:

| Situação | Dependência recomendada |
|---|---|
| App Spring Boot, sem Vault | `env-governance-starter` |
| App Spring Boot, com Vault | `env-governance-vault-starter` |
| App não-Spring (Quarkus, Micronaut, Java puro, etc.) | `env-governance-java` |
| App não-Spring, com Vault | `env-governance-java` + `env-governance-vault` |
| Implementar fonte customizada de segredos | `env-governance-java` |

### Descrição de cada módulo

| Módulo | Descrição | Deps em runtime |
|---|---|---|
| `env-governance-java` | SPI `EnvVarSource`, `GovernanceContext`, `EnvFileReader` e o motor de contrato (`EnvContract`, `Validators`, parser `.properties`). Zero dependências externas — apenas JDK 21. | Nenhuma |
| `env-governance-core` | `EnvironmentPostProcessor`s Spring Boot que alimentam os registries estáticos no startup. | `env-governance-java`, `spring-boot` |
| `env-governance-autoconfigure` | Auto-configuração Spring Boot, reporter de logs e endpoint Actuator. | `env-governance-core`, `spring-boot` |
| `env-governance-starter` | Agregador: `env-governance-core` + `env-governance-autoconfigure`. | (transitivo) |
| `env-governance-vault` | `VaultEnvVarSource` — lê segredos do HashiCorp Vault. Zero dependências externas — apenas JDK 21 + `env-governance-java`. | `env-governance-java` |
| `env-governance-vault-starter` | Agregador: `env-governance-starter` + `env-governance-vault`. | (transitivo) |

> **Nota:** `env-governance-vault` não depende do Spring Boot. Ele funciona tanto em apps Spring Boot (carregado automaticamente via `ServiceLoader` pelo core) quanto em apps não-Spring (instanciado diretamente ou via `ServiceLoader`).

---

## Instalação e uso básico

### Apps Spring Boot

Adicione o starter ao `pom.xml`. Nenhuma configuração adicional é necessária — a auto-configuração ativa tudo automaticamente.

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

O diagnóstico aparece nos logs assim que a aplicação inicia. Para declarar variáveis obrigatórias, use placeholders sem default no `application.yml`:

```yaml
spring:
  datasource:
    url: ${DB_URL}              # obrigatória — startup falha se ausente
    password: ${DB_PASSWORD}    # obrigatória — startup falha se ausente
    username: ${DB_USER:app}    # opcional — usa "app" se ausente
```

O `RequiredEnvCheckEnvironmentPostProcessor` detecta as variáveis `DB_URL` e `DB_PASSWORD` como obrigatórias e aborta o startup com uma mensagem listando **todas** as variáveis faltantes de uma vez, antes de qualquer bean ser criado.

---

### Apps Spring Boot com Vault

Use o starter completo que inclui a integração com o HashiCorp Vault:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-vault-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Configure o Vault no `application.yml`:

```yaml
env:
  governance:
    sources:
      vault:
        address: "${VAULT_ADDR}"
        auth:
          method: token           # ou approle
          token: "${VAULT_TOKEN}"
        paths:
          - secret/myapp          # KV v2: env-governance insere /data/ automaticamente
          - secret/shared
```

Os segredos lidos do Vault ficam disponíveis como propriedades Spring e são verificados pelo `RequiredEnvCheckEnvironmentPostProcessor` — exatamente como variáveis do SO.

```yaml
# Referencia segredos que vêm do Vault:
spring:
  datasource:
    password: ${DB_PASSWORD}    # vem do Vault: secret/myapp → DB_PASSWORD
```

---

### Apps não-Spring

Para aplicações que não usam o ecossistema Spring (Quarkus, Micronaut sem compat. Spring, Java puro, etc.), use apenas o módulo java:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-java</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Chame `GovernanceContext.builder()` no início do `main()`, antes de qualquer inicialização do framework:

```java
public static void main(String[] args) {
    GovernanceContext.builder()
        .require("DB_URL")
        .require("API_KEY")
        .build()
        .verify();  // lança MissingRequiredEnvironmentVariablesException se faltar algo

    // inicialização normal da aplicação
    startApp(args);
}
```

Além de presença, é possível validar **valor** e declarar regras condicionais na mesma cadeia fluente — ver [Contrato declarativo de configuração](#contrato-declarativo-de-configuração):

```java
GovernanceContext.builder()
    .require("DB_URL").asUrl()
    .require("SERVER_PORT").asPort()
    .optional("RETRY_COUNT").asInt().min(1).max(10)
    .require("AUTH_METHOD").oneOf("token", "approle")
    .requireIf("AUTH_METHOD=approle", "ROLE_ID", "SECRET_ID")
    .build()
    .verify();  // lança EnvContractValidationException se algum valor for inválido
```

#### Carregando arquivos de configuração

Se sua aplicação usa arquivos `.env` ou `.properties` para configuração local:

```java
GovernanceContext.builder()
    .require("DB_URL")
    .require("API_KEY")
    .fromDotEnv(Path.of(".env"))              // KEY=VALUE, comentários com #
    .fromProperties(Path.of("app.properties")) // formato java.util.Properties
    .build()
    .verify();
```

Arquivos são ignorados silenciosamente se não existirem — seguro usar em todos os ambientes.

**Formato `.env` suportado:**

```dotenv
# Comentários são ignorados
DB_URL=jdbc:postgresql://localhost:5432/mydb
API_KEY="minha-chave-com-espacos"
APP_SECRET='outro-valor-entre-aspas'
```

#### Verificação sem lançar exceção

Para controlar o comportamento manualmente:

```java
GovernanceResult result = GovernanceContext.builder()
    .require("DB_URL")
    .require("API_KEY")
    .build()
    .check();  // não lança — retorna resultado

if (result.hasGaps()) {
    result.missingRequired().forEach(name ->
        System.err.println("Variável ausente: " + name));
    result.violations().forEach(v ->            // valores inválidos e condicionais
        System.err.println("Violação de contrato: " + v.message()));
    System.exit(1);
}
```

> `GovernanceResult.hasGaps()` já considera as violações de contrato. `missingRequired()` lista as ausências (incluindo condicionais promovidas); `violations()` traz todas as `ContractViolation` (`MISSING`, `CONDITIONAL_MISSING`, `INVALID`).

#### Ordem de prioridade

Quando a mesma variável existe em múltiplas fontes, a prioridade é (maior para menor):

1. Variáveis do SO (`System.getenv()`)
2. Fontes dinâmicas (`EnvVarSource`) na ordem de `getOrder()`
3. Arquivos (`.env`, `.properties`) na ordem de registro

---

### Apps não-Spring com Vault

Adicione `env-governance-vault` ao lado de `env-governance-java`:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-java</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-vault</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

O `VaultEnvVarSource` é descoberto automaticamente via `ServiceLoader` quando presente no classpath — nenhuma configuração adicional de código é necessária. Configure via variáveis de ambiente:

```dotenv
VAULT_ADDR=https://vault.example.com
VAULT_AUTH_METHOD=token
VAULT_TOKEN=hvs.xxxxxxxx
VAULT_PATHS=secret/myapp,secret/shared
```

```java
public static void main(String[] args) {
    GovernanceContext.builder()
        .require("DB_URL")      // pode vir do Vault: secret/myapp → DB_URL
        .require("API_KEY")     // pode vir do Vault: secret/shared → API_KEY
        .build()
        .verify();

    startApp(args);
}
```

Para registrar o Vault explicitamente (desabilitando a descoberta automática via `ServiceLoader`):

```java
GovernanceContext.builder()
    .require("DB_URL")
    .withoutServiceLoaderDiscovery()  // controle total sobre fontes
    .addSource(new VaultEnvVarSource())
    .build()
    .verify();
```

---

## Como funciona

### Spring Boot — pipeline de inicialização

```
1. ConfigDataEnvironmentPostProcessor (Spring Boot, +10)
   └─ Carrega application*.yml/properties nas PropertySources

2. DeclaredVarsScanner (+15)
   └─ Para cada propriedade nas PropertySources do classpath:
       ├─ Registra var derivada pela CHAVE (relaxed binding):
       │     spring.datasource.password → SPRING_DATASOURCE_PASSWORD
       └─ Escaneia o VALOR em busca de ${PLACEHOLDER}:
             ${DB_PASSWORD}   → explicit=true, hasDefault=false (obrigatória)
             ${DB_USER:app}   → explicit=true, hasDefault=true  (opcional)

3. EnvContractScanner (+16)
   └─ Carrega env-governance.properties / env-governance.yml do classpath
   └─ Junta specs/conditionals contribuídos pelas fontes SPI (ex.: Vault)
   └─ Faz merge no ContractRegistry (opcional — sem arquivo, contrato vazio)

4. EnvVarSourceLoaderPostProcessor (+18)
   └─ Descobre EnvVarSource via java.util.ServiceLoader
   └─ Chama isAvailable() → load() para cada fonte (Vault, SO, etc.)
   └─ Injeta PropertySources após "systemEnvironment"

5. RequiredEnvCheckEnvironmentPostProcessor (+20)
   └─ Verifica vars com explicit=true AND hasDefault=false
   └─ + nomes obrigatórios (estáticos e condicionais) do contrato
   └─ Lança MissingRequiredEnvironmentVariablesException com TODAS as ausentes

6. EnvContractValidationEnvironmentPostProcessor (+21)
   └─ Valida o VALOR das vars declaradas contra o contrato (int/port/url/oneOf/…)
   └─ Grava ContractViolation(INVALID) no ContractRegistry
   └─ Lança EnvContractValidationException se fail-on-invalid=true (default)

7. UsageTrackingEnvironmentPostProcessor (LOWEST_PRECEDENCE)
   └─ Envolve PropertySources para rastrear chaves lidas em runtime

8. EnvVarUsageReporter (ApplicationReadyEvent)
   └─ Cruza DeclaredVarsRegistry × EnvVarSourceRegistry × ContractRegistry
       ├─ INFO  → gaps acionáveis (inclui [INVALID])
       └─ DEBUG → diagnóstico completo com atribuição por fonte
```

#### Relaxed Binding (chave)

Toda chave de propriedade é convertida para o nome de variável equivalente do Spring Boot:

| Chave da propriedade | Variável de ambiente |
|---|---|
| `spring.application.name` | `SPRING_APPLICATION_NAME` |
| `server.ssl.key-store` | `SERVER_SSL_KEY_STORE` |
| `spring.datasource.hikari.max-pool-size` | `SPRING_DATASOURCE_HIKARI_MAX_POOL_SIZE` |

#### Placeholder explícito (valor)

Se o valor da propriedade contém `${VAR_NAME}` ou `${VAR_NAME:default}`, o `VAR_NAME` é registrado com o nome literal — que **pode diferir** do nome derivado pela chave:

```yaml
bridge:
  poller:
    max-messages-per-poll: ${BRIDGE_MAX_MESSAGES_PER_POLL:100}
```

→ registra **dois** nomes distintos:
- `BRIDGE_POLLER_MAX_MESSAGES_PER_POLL` — derivado da chave (relaxed binding)
- `BRIDGE_MAX_MESSAGES_PER_POLL` — nome explícito do placeholder

---

### Apps não-Spring — GovernanceContext

```
1. GovernanceContext.builder().require("VAR").build()
   └─ Registra variáveis obrigatórias declaradas programaticamente

2. context.check() / context.verify()
   └─ Monta mapa de ambiente:
       a. Base: System.getenv()
       b. Fontes EnvVarSource (ServiceLoader ou explícitas), por getOrder():
          └─ load(Map<String,String>) → putIfAbsent (S.O. vence)
       c. Arquivos .env/.properties → putIfAbsent (menor prioridade)
   └─ Verifica vars declared via require() contra o mapa
   └─ verify() lança MissingRequiredEnvironmentVariablesException com lista completa
      check() retorna GovernanceResult sem lançar
```

---

## Contrato declarativo de configuração

A detecção de presença responde "a variável existe?". O **contrato** vai além e responde "o valor é válido?" e "as regras cruzadas foram satisfeitas?". Ele cobre três lacunas:

1. **Validação de valor** — `PORT=abc`, `DB_URL=""` ou um enum fora do conjunto permitido passam pela checagem de presença e só falham em runtime. Com o contrato, a falha para no startup.
2. **Requisitos condicionais (cross-field)** — regras como "se `AUTH_METHOD=approle`, então `ROLE_ID` e `SECRET_ID` são obrigatórias".
3. **Contrato explícito e versionável** — um artefato que declara o que a aplicação espera (nome, obrigatoriedade, tipo, valores permitidos, descrição, sensibilidade).

> **Opcional e aditivo.** Sem contrato, o comportamento é idêntico ao de antes (só presença). Quando presente, ele *enriquece* — nunca substitui — o que já é inferido do ambiente. O núcleo `env-governance-java` continua **zero-dep**.

Um mesmo modelo (`EnvContract`) é alimentado por três frentes que convergem: a **API fluente** (apps não-Spring), um **arquivo declarativo** (`env-governance.yml`/`.properties`) e **contribuições de fontes** (`EnvVarSource`, ex.: o Vault). A avaliação é pura — recebe o mapa de ambiente resolvido e devolve as violações — então Spring e não-Spring compartilham exatamente a mesma lógica.

### Modo 1: API fluente (não-Spring)

`require(...)` e `optional(...)` abrem um sub-builder escopado àquela variável, onde se anexam as restrições de valor. Os demais métodos do builder continuam acessíveis, então cadeias antigas permanecem válidas.

```java
GovernanceContext.builder()
    .require("DB_URL").asUrl().describedAs("URL de conexão com o banco")
    .require("SERVER_PORT").asPort()
    .optional("RETRY_COUNT").asInt().min(1).max(10)
    .require("AUTH_METHOD").oneOf("token", "approle")
    .require("API_KEY").sensitive()
    .requireIf("AUTH_METHOD=approle", "ROLE_ID", "SECRET_ID")
    .build()
    .verify();
```

- `verify()` lança `EnvContractValidationException` (com todas as violações) quando há valor inválido; lança `MissingRequiredEnvironmentVariablesException` quando há apenas ausências.
- `check()` não lança — retorna `GovernanceResult`, cujo `violations()` traz a lista de `ContractViolation` e `hasGaps()` já considera as violações.

### Modo 2: arquivo declarativo

Coloque o contrato no classpath (`src/main/resources`). O scanner tenta, nesta ordem, `env-governance.properties` e depois `env-governance.yml` — o primeiro encontrado vence. O arquivo é totalmente opcional.

**`env-governance.yml`** (YAML — requer o parser do `env-governance-core`, que traz SnakeYAML via Spring Boot):

```yaml
vars:
  DB_URL:
    required: true
    type: url
    description: "URL de conexão com o banco de dados"
  SERVER_PORT:
    type: port
  AUTH_METHOD:
    required: true
    oneOf: [token, approle]
  API_KEY:
    required: true
    sensitive: true
  TIMEOUT:
    type: int
    min: 1
    max: 300

conditionals:
  - when: "AUTH_METHOD=approle"
    require: [VAULT_ROLE_ID, VAULT_SECRET_ID]
```

**`env-governance.properties`** (formato flat — suportado em qualquer ambiente, inclusive apps não-Spring zero-dep):

```properties
# Especificações de variáveis — padrão VARNAME.atributo=valor
DB_URL.required=true
DB_URL.type=url
DB_URL.description=URL de conexão com o banco de dados

SERVER_PORT.type=port

AUTH_METHOD.required=true
AUTH_METHOD.oneOf=token,approle

API_KEY.required=true
API_KEY.sensitive=true

RETRY_COUNT.type=int
RETRY_COUNT.min=1
RETRY_COUNT.max=10

# Requisitos condicionais — requireIf.GRUPO.when / requireIf.GRUPO.require
requireIf.approle.when=AUTH_METHOD=approle
requireIf.approle.require=VAULT_ROLE_ID,VAULT_SECRET_ID
```

Formatos malformados lançam `EnvContractParseException` com o nome do recurso e a causa — nunca falham em silêncio.

> **Extensibilidade do parser.** O carregamento usa o SPI `EnvContractParser`, descoberto via `ServiceLoader`. `env-governance-java` registra o parser `.properties`; `env-governance-core` registra o YAML. Para suportar outro formato, implemente `EnvContractParser` e registre-o em `META-INF/services/`.

### Atributos de uma variável

| Atributo | `.properties` | YAML | Efeito |
|---|---|---|---|
| Obrigatoriedade | `NOME.required=true` | `required: true` | Ausência vira gap `[REQUIRED]` (default: `false`). |
| Tipo | `NOME.type=port` | `type: port` | Adiciona o validador correspondente: `port`, `url`, `int`, `boolean`, `non-empty`. |
| Conjunto permitido | `NOME.oneOf=a,b` | `oneOf: [a, b]` | Valor deve ser um dos listados (comparação exata). |
| Regex | `NOME.regex=^\\d+$` | `regex: "^\\d+$"` | Valor deve casar totalmente com a expressão. |
| Mínimo / máximo | `NOME.min=1` / `NOME.max=10` | `min: 1` / `max: 10` | Faixa inteira (inclusiva). |
| Não-vazio | `NOME.nonEmpty=true` | `nonEmpty: true` | Rejeita valor em branco. |
| Descrição | `NOME.description=...` | `description: "..."` | Texto livre para relatórios. |
| Sensível | `NOME.sensitive=true` | `sensitive: true` | Mascara o valor em relatórios. |

Os validadores só rodam quando a variável **está presente** — uma variável `optional` com tipo inválido gera `[INVALID]`; ausente, é ignorada.

### Requisitos condicionais

Um requisito condicional promove um conjunto de variáveis a obrigatórias **apenas quando** uma condição sobre o ambiente resolvido é satisfeita. A forma simples é uma igualdade `CHAVE=valor`:

```java
.requireIf("AUTH_METHOD=approle", "ROLE_ID", "SECRET_ID")
```

Para lógica arbitrária, use o overload com `Predicate`:

```java
.requireIf(env -> "prod".equals(env.get("APP_ENV")) && !env.containsKey("SENTRY_DSN"),
           "APP_ENV=prod exige observabilidade", "SENTRY_DSN")
```

Quando a condição é satisfeita e a variável está ausente, ela vira uma violação `CONDITIONAL_MISSING` — reportada junto das obrigatórias ausentes e abortando o startup no mesmo estágio.

### Validadores embutidos

Todos implementados apenas com o JDK 21 (`com.example.envgovernance.contract.Validators`):

| Validador | Fluente / `type` | Regra |
|---|---|---|
| Inteiro | `.asInt()` / `int` | Inteiro decimal (`Long.parseLong`). |
| Porta | `.asPort()` / `port` | Inteiro no intervalo `1–65535`. |
| URL | `.asUrl()` / `url` | URL absoluta com esquema (via `java.net.URI`). |
| Booleano | `.asBoolean()` / `boolean` | `true` ou `false` (case-insensitive). |
| Não-vazio | `.nonEmpty()` / `non-empty` | Valor não em branco. |
| Conjunto | `.oneOf(...)` | Pertence ao conjunto (comparação exata). |
| Regex | `.matches(regex)` | Casa totalmente com o padrão. |
| Mínimo | `.min(n)` | Inteiro `≥ n`. |
| Máximo | `.max(n)` | Inteiro `≤ n`. |
| Customizado | `.withValidator(v)` | Qualquer `ValueValidator` próprio. |

### Contribuições do Vault

O `VaultEnvVarSource` deixou de embutir suas regras condicionais em código: agora as contribui ao contrato geral via `contributedSpecs()` / `contributedConditionals()`. Quando `VAULT_ADDR` está presente, ele declara `VAULT_AUTH_METHOD` como `oneOf(token, approle)`, `VAULT_ADDR` como `url`, e condicionais que exigem `VAULT_ROLE_ID`+`VAULT_SECRET_ID` (approle) ou `VAULT_TOKEN` (token). As guardas no `VaultClient` permanecem como defesa em profundidade.

---

## Configuração

### Propriedades Spring Boot

Adicione ao `application.yml` apenas o que precisar sobrescrever. Todos os defaults são razoáveis.

```yaml
env:
  governance:
    enabled: true              # false desliga toda a lib (útil em testes de integração)
    fail-on-missing: true      # false desabilita o early check de obrigatórias
    report-on-ready: true      # false suprime o relatório no ApplicationReadyEvent
    report-orphan-vars: true   # false suprime a seção [UNUSED]
    orphan-report-limit: 50    # limita vars não utilizadas exibidas para evitar spam
    system-var-mode: collapse  # collapse | hide | full — tratamento das vars de S.O./runner
    system-var-patterns: []    # padrões extras p/ classificar como S.O.: FOO* (prefixo) ou FOO (exato)
    contract:
      enabled: true            # habilita a validação do contrato declarativo
      fail-on-invalid: true    # aborta o startup se houver valor inválido
      location: classpath:env-governance.yml   # reservado (ver nota)
      strict-unknown-vars: false               # reservado (ver nota)
```

| Propriedade | Default | Descrição |
|---|---|---|
| `env.governance.enabled` | `true` | Desliga toda a lib. Útil em perfis de teste. |
| `env.governance.fail-on-missing` | `true` | Controla o early check de variáveis obrigatórias. Defina `false` em testes de integração que não precisam de todas as vars. |
| `env.governance.report-on-ready` | `true` | Controla se o relatório de diagnóstico é produzido no `ApplicationReadyEvent`. Desabilite se quiser apenas o endpoint Actuator. |
| `env.governance.report-orphan-vars` | `true` | Inclui a seção `[UNUSED]`. Desabilite em ambientes com muitas vars de infraestrutura irrelevantes. |
| `env.governance.orphan-report-limit` | `50` | Limita quantas vars não utilizadas são exibidas. |
| `env.governance.system-var-mode` | `collapse` | Como tratar variáveis de infraestrutura (S.O., JVM, Maven, Kubernetes, runners de CI) na seção `[UNUSED]`. Ver tabela abaixo. |
| `env.governance.system-var-patterns` | `[]` | Padrões extras para classificar variáveis como infraestrutura, somados à lista curada embutida. `FOO*` = prefixo, `FOO` = nome exato (case-insensitive). Ex.: `ACME_CI_*` para vars do runner de CI da sua organização. |
| `env.governance.contract.enabled` | `true` | Habilita o carregamento e a validação do [contrato declarativo](#contrato-declarativo-de-configuração). Com `false`, o scanner e a validação de valor são pulados. |
| `env.governance.contract.fail-on-invalid` | `true` | Aborta o startup (`EnvContractValidationException`) quando há valor inválido. Com `false`, as violações apenas aparecem como `[INVALID]` no relatório. |
| `env.governance.contract.location` | `classpath:env-governance.yml` | ⚠️ **Reservado.** O scanner atual carrega os locais fixos `env-governance.properties` e `env-governance.yml` do classpath; esta propriedade ainda não é consumida. |
| `env.governance.contract.strict-unknown-vars` | `false` | ⚠️ **Reservado.** Campo previsto para reportar variáveis presentes fora do contrato; ainda não implementado. |

**Modos de `system-var-mode`:**

| Modo | Efeito na seção `[UNUSED]` |
|---|---|
| `collapse` (padrão) | Separa o ruído de S.O./runner das órfãs acionáveis: as acionáveis são listadas em `[UNUSED]`, e as de infraestrutura são colapsadas em uma única linha `[SYSTEM]` com contagem e amostra. A lista completa continua disponível em DEBUG. |
| `hide` | Remove totalmente as variáveis de infraestrutura do relatório INFO. Só as órfãs acionáveis aparecem em `[UNUSED]`. |
| `full` | Comportamento legado: lista **todas** as variáveis não utilizadas em `[UNUSED]`, sem separar infraestrutura. |

> **Por que isso importa:** em testes e containers, o S.O. contribui dezenas de variáveis (`PATH`, `JAVA_HOME`, `LC_*`, `SUREFIRE_*`, etc.) que nunca casam com propriedades da aplicação e inundam o `[UNUSED]`. O modo `collapse` mantém a visibilidade (uma linha) sem o spam. Se o seu runner de CI injeta variáveis próprias que ainda aparecem como órfãs, adicione o prefixo em `system-var-patterns` — sem precisar tocar na lib.

**Desabilitando por perfil:**

```yaml
# application-test.yml
env:
  governance:
    enabled: false  # silencia tudo nos testes de integração
```

---

### Configuração do Vault

O Vault usa um padrão de dois níveis: chave Spring primeiro, variável de ambiente do SO como fallback. Isso permite configurar via `application.yml` (recomendado para Spring Boot) ou via variáveis de ambiente puras (recomendado para apps não-Spring e CI/CD).

#### Via `application.yml` (Spring Boot)

```yaml
env:
  governance:
    sources:
      vault:
        address: "${VAULT_ADDR}"                     # URL do servidor Vault
        auth:
          method: token                              # token ou approle
          token: "${VAULT_TOKEN}"                    # para auth por token
          # role-id: "${VAULT_ROLE_ID}"              # para AppRole
          # secret-id: "${VAULT_SECRET_ID}"          # para AppRole
        paths:
          - secret/myapp                             # KV v2: /data/ inserido automaticamente
          - secret/shared                            # primeiro caminho tem prioridade
        kv-version: 2                                # 1 ou 2 (default: 2)
        namespace: ""                                # Vault Enterprise: ex. "my-org/dev"
        timeout-seconds: 5                           # timeout da conexão HTTP
        tls:
          skip-verify: false                         # true APENAS em desenvolvimento local
```

#### Via variáveis de ambiente (não-Spring e CI/CD)

| Variável | Equivalente Spring | Descrição |
|---|---|---|
| `VAULT_ADDR` | `env.governance.sources.vault.address` | URL do servidor Vault |
| `VAULT_AUTH_METHOD` | `env.governance.sources.vault.auth.method` | `token` ou `approle` |
| `VAULT_TOKEN` | `env.governance.sources.vault.auth.token` | Token de autenticação |
| `VAULT_ROLE_ID` | `env.governance.sources.vault.auth.role-id` | AppRole: Role ID |
| `VAULT_SECRET_ID` | `env.governance.sources.vault.auth.secret-id` | AppRole: Secret ID |
| `VAULT_PATHS` | `env.governance.sources.vault.paths` | Caminhos separados por vírgula |
| `VAULT_KV_VERSION` | `env.governance.sources.vault.kv-version` | `1` ou `2` (default: `2`) |
| `VAULT_NAMESPACE` | `env.governance.sources.vault.namespace` | Vault Enterprise namespace |
| `VAULT_TIMEOUT_SECONDS` | `env.governance.sources.vault.timeout-seconds` | Timeout HTTP (default: `5`) |
| `VAULT_TLS_SKIP_VERIFY` | `env.governance.sources.vault.tls.skip-verify` | `true` só em dev local |

#### Autenticação AppRole

```yaml
# application.yml
env:
  governance:
    sources:
      vault:
        address: "${VAULT_ADDR}"
        auth:
          method: approle
          role-id: "${VAULT_ROLE_ID}"
          secret-id: "${VAULT_SECRET_ID}"
        paths:
          - secret/myapp
```

#### Múltiplos caminhos e prioridade

Quando o mesmo segredo existe em mais de um caminho, o **primeiro caminho** na lista vence:

```yaml
paths:
  - secret/myapp     # específico: tem prioridade
  - secret/shared    # genérico: segredos não presentes em myapp vêm daqui
```

#### Comportamento em caso de falha do Vault

Controle o que acontece se o Vault não responder:

```yaml
env:
  governance:
    sources:
      vault:
        on-failure: fail   # padrão — aborta o startup se o Vault não responder
        # on-failure: warn # loga aviso e continua (varNames vazio, sem segredos injetados)
        # on-failure: skip # loga aviso, não registra a fonte
```

---

## Interpretando os logs

### Nível INFO — gaps acionáveis

Aparece sempre que há algo a reportar. Usa `log.error` para casos críticos, `log.warn` para alertas.

**Com gaps:**

```
INFO  ===== ENV GOVERNANCE: GAPS DE CONFIGURAÇÃO =====

ERROR [REQUIRED] Variáveis obrigatórias ausentes no SO/container (1):
ERROR   DB_PASSWORD                                    →  spring.datasource.password  [application.yml]

ERROR [INVALID] Variáveis com valor inválido conforme contrato (1):
ERROR   SERVER_PORT                                    →  SERVER_PORT: porta fora do intervalo 1–65535 ("70000")

WARN  [FALLBACK] Variáveis ausentes no SO/container, usando valor padrão (2):
WARN    BRIDGE_MAX_MESSAGES_PER_POLL                   →  bridge.poller.max-messages-per-poll  [application.yml]
WARN    REDIS_PORT                                     →  spring.data.redis.port  [application-redis.yml]

WARN  [NO VALUE] Propriedades sem valor no YAML e sem variável de ambiente (1):
WARN    SPRING_DATASOURCE_URL                          →  spring.datasource.url  [application.yml]

WARN  [UNUSED] Variáveis sem correspondência na aplicação (2):
WARN    DB_PASSWORImD                                   <system-env>
WARN    LEGACY_TIMEOUT                                  <system-env>

INFO  [SYSTEM] 146 variáveis de S.O./runner ignoradas: COMPUTERNAME, HOME, JAVA_HOME, LANG, PATH, TEMP, … (lista completa em DEBUG)

INFO  =================================================
```

> No modo padrão `collapse`, a seção `[UNUSED]` mostra apenas variáveis **acionáveis** (typos, propriedades removidas). No exemplo, `DB_PASSWORImD` é um typo de `DB_PASSWORD`. As variáveis de infraestrutura (`PATH`, `JAVA_HOME`, etc.) são colapsadas na linha `[SYSTEM]`. Use `env.governance.system-var-mode: full` para voltar ao comportamento de listar tudo.

**Sem gaps:**

```
INFO  [ENV GOVERNANCE] Sem gaps de configuração detectados.
```

### Nível DEBUG — diagnóstico completo

Ative adicionando ao `application.yml`:

```yaml
logging:
  level:
    com.example.envgovernance: DEBUG
```

```
DEBUG ===== ENV GOVERNANCE: DIAGNÓSTICO COMPLETO (startup) =====

DEBUG [APPLICATION] Variáveis de ambiente potenciais (6 var(s)):
DEBUG   BRIDGE_MAX_MESSAGES_PER_POLL       [${} optional]  →  bridge.poller.max-messages-per-poll  [application.yml]
DEBUG   BRIDGE_POLLER_MAX_MESSAGES_PER_POLL [key]           →  bridge.poller.max-messages-per-poll  [application.yml]
DEBUG   DB_PASSWORD                        [${} required]  →  spring.datasource.password           [application.yml]
DEBUG   SERVER_PORT                        [${} optional]  →  server.port                          [application.yml]
DEBUG   SPRING_APPLICATION_NAME            [key]           →  spring.application.name              [application.yml]
DEBUG   SPRING_DATASOURCE_URL              [key/no-value]  →  spring.datasource.url                [application.yml]

DEBUG [ENVIRONMENT] Variáveis definidas (152 var(s) — system-env: 151, vault:secret/myapp: 1):
DEBUG   DB_PASSWORD        ← vault:secret/myapp
DEBUG   HOME               ← system-env
DEBUG   JAVA_HOME          ← system-env
DEBUG   SERVER_PORT        ← system-env
DEBUG   ...

DEBUG [ACTIVE] Substituições ativas: fontes sobrescrevendo propriedades (2 var(s)):
DEBUG   DB_PASSWORD              →  spring.datasource.password  [vault:secret/myapp]
DEBUG   SERVER_PORT              →  server.port                 [system-env]

DEBUG [UNUSED] Variáveis do ambiente não utilizadas pela aplicação (150 var(s.)):
DEBUG   HOME
DEBUG   JAVA_HOME
DEBUG   PATH
DEBUG   ...

DEBUG ==========================================================
```

### Marcadores na seção `[APPLICATION]`

| Marcador | Origem | Significado |
|---|---|---|
| `[key]` | Chave da propriedade | Var derivada por relaxed binding. O YAML tem valor — a env var é opcional. |
| `[key/no-value]` | Chave da propriedade | Var derivada, mas a propriedade está sem valor no YAML. |
| `[${} optional]` | Placeholder no valor | Var explícita com default: `${VAR:fallback}`. |
| `[${} required]` | Placeholder no valor | Var explícita sem default: `${VAR}`. Obrigatória. |

---

## Endpoint Actuator

Quando `spring-boot-actuator` está no classpath, o endpoint é registrado automaticamente. Basta expô-lo:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: env-governance
```

Acesse via `GET /actuator/env-governance`.

### Estrutura da resposta

```json
{
  "gaps": {
    "required": [
      {
        "name": "DB_PASSWORD",
        "propertyKey": "spring.datasource.password",
        "sourceFile": "application.yml"
      }
    ],
    "fallback": [
      {
        "name": "BRIDGE_MAX_MESSAGES_PER_POLL",
        "propertyKey": "bridge.poller.max-messages-per-poll",
        "sourceFile": "application.yml"
      }
    ],
    "noValue": [],
    "invalid": [
      {
        "name": "SERVER_PORT",
        "message": "SERVER_PORT: porta fora do intervalo 1–65535 (\"70000\")",
        "value": "70000"
      }
    ]
  },
  "unused": ["HOME", "JAVA_HOME", "PATH"],
  "activeOverrides": [
    {
      "envVar": "SERVER_PORT",
      "propertyKey": "server.port",
      "sourceFile": "application.yml",
      "source": "system-env"
    }
  ],
  "applicationVars": [
    {
      "name": "DB_PASSWORD",
      "propertyKey": "spring.datasource.password",
      "sourceFile": "application.yml",
      "origin": "placeholder-required"
    }
  ],
  "sources": [
    { "name": "system-env",          "varCount": 152, "priority": 0  },
    { "name": "vault:secret/myapp",  "varCount": 5,   "priority": 10 }
  ]
}
```

| Campo | Conteúdo |
|---|---|
| `gaps.required` | Placeholders `${VAR}` sem default cujo `VAR` não está em nenhuma fonte. |
| `gaps.fallback` | Placeholders `${VAR:default}` cujo `VAR` não está em nenhuma fonte (usando fallback). |
| `gaps.noValue` | Propriedades sem valor no YAML e sem env var correspondente. |
| `gaps.invalid` | Variáveis presentes cujo valor viola o [contrato declarativo](#contrato-declarativo-de-configuração). Cada item traz `name`, `message` e `value` observado (vazio se ausente). |
| `unused` | Vars de todas as fontes sem correspondência em nenhuma propriedade da aplicação. |
| `activeOverrides` | Vars que estão ativamente sobrescrevendo propriedades, com atribuição da fonte. |
| `applicationVars` | Lista completa com `origin`: `key-derived`, `key-derived/no-value`, `placeholder-optional`, `placeholder-required`. |
| `sources` | Fontes ativas e contagem de variáveis, ordenadas por prioridade. |

---

## Implementando uma fonte customizada (SPI)

Para integrar com outras fontes de segredos (AWS Secrets Manager, Azure Key Vault, GCP Secret Manager, etc.), implemente a interface `EnvVarSource` do módulo `env-governance-java`:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-java</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

```java
package com.example.myapp.secrets;

import com.example.envgovernance.spi.EnvVarSource;

import java.util.Map;
import java.util.Set;

public final class AwsSecretsManagerSource implements EnvVarSource {

    private volatile Set<String> varNames = Set.of();

    @Override
    public String name() {
        return "aws-secrets-manager";
    }

    @Override
    public boolean isAvailable(Map<String, String> environment) {
        // Retorne false se a configuração mínima não estiver presente
        return environment.containsKey("AWS_SECRET_ARN")
                || environment.containsKey("aws.secrets.arn");
    }

    @Override
    public Map<String, String> load(Map<String, String> environment) {
        String arn = environment.getOrDefault("aws.secrets.arn",
                environment.get("AWS_SECRET_ARN"));

        // Busque os segredos do AWS...
        Map<String, String> secrets = fetchFromAws(arn);

        this.varNames = Set.copyOf(secrets.keySet());
        return secrets;
    }

    @Override
    public Set<String> getVarNames() {
        return varNames;
    }

    @Override
    public int getOrder() {
        return 20; // após Vault (10), antes do padrão de terceiros (100)
    }
}
```

Registre a implementação no arquivo de serviços do Java (`ServiceLoader`):

```
# src/main/resources/META-INF/services/com.example.envgovernance.spi.EnvVarSource
com.example.myapp.secrets.AwsSecretsManagerSource
```

A fonte é descoberta automaticamente:
- **Spring Boot** — pelo `EnvVarSourceLoaderPostProcessor` no startup.
- **Apps não-Spring** — pelo `GovernanceContext.builder().build()` (a menos que `.withoutServiceLoaderDiscovery()` seja usado).

### Contrato da interface

| Método | Quando é chamado | O que deve retornar |
|---|---|---|
| `isAvailable(env)` | Antes de `load()` | `false` se a configuração mínima não estiver presente — ignora a fonte silenciosamente |
| `load(env)` | Após `isAvailable()` retornar `true` | Mapa de variáveis a injetar; mapa vazio se a fonte não precisa injetar nada |
| `getVarNames()` | Após `load()` completar | Set de todos os nomes de variáveis gerenciados por esta fonte |
| `isSensitive(varName)` | Pelos reporters | `true` para suprimir o valor nos logs (default: detecta PASSWORD/SECRET/TOKEN/KEY/CREDENTIAL) |
| `getOrder()` | Para ordenação | Inteiro — menor valor = maior prioridade |
| `contributedSpecs()` | Pelo scanner de contrato | `List<VarSpec>` que a fonte adiciona ao [contrato](#contrato-declarativo-de-configuração) (default: vazio) |
| `contributedConditionals()` | Pelo scanner de contrato | `List<ConditionalRequirement>` da fonte (default: vazio) |

Os dois últimos são métodos `default` (retornam vazio) — implementações existentes seguem intactas. Use-os para que a fonte declare as próprias regras de validação, como faz o `VaultEnvVarSource`. As contribuições da SPI têm menor precedência que o arquivo/builder no merge do contrato.

**Comportamento em caso de falha:** controlado pela propriedade `env.governance.sources.<nome>.on-failure` (Spring Boot) ou tratado pela aplicação (não-Spring, via `GovernanceResult.hasGaps()`).

---

## Referência de gaps

### `[REQUIRED]` — Crítico (`log.error`)

**O que é:** a propriedade usa `${VAR}` *sem valor de fallback* e `VAR` não está em nenhuma fonte (SO, Vault, etc.).

O Spring Boot tentará resolver o placeholder ao injetar a propriedade. Se nenhum bean ler essa propriedade no startup, a aplicação pode subir — mas falhará silenciosamente em runtime.

**O que fazer:** defina a variável no SO/container ou configure uma fonte de segredos que a forneça.

---

### `[INVALID]` — Crítico (`log.error`)

**O que é:** a variável está presente, mas o valor viola uma regra do [contrato declarativo](#contrato-declarativo-de-configuração) — tipo (`port`, `url`, `int`, `boolean`), conjunto `oneOf`, faixa `min`/`max`, `regex` ou `non-empty`.

Só aparece quando há um contrato declarado. Com `env.governance.contract.fail-on-invalid=true` (padrão), o startup é abortado com `EnvContractValidationException`; com `false`, a violação apenas é reportada aqui.

**O que fazer:** corrija o valor no SO/container/fonte de segredos, ou ajuste o contrato se a regra estiver desatualizada.

> Requisitos condicionais não satisfeitos (`CONDITIONAL_MISSING`) são reportados junto de `[REQUIRED]`, pois representam ausência de variável — não valor inválido.

---

### `[FALLBACK]` — Atenção (`log.warn`)

**O que é:** a propriedade usa `${VAR:valor_padrão}` e `VAR` não está em nenhuma fonte. O valor padrão declarado no YAML está sendo utilizado.

Pode ser intencional (porta padrão em ambiente local) ou um esquecimento em produção.

**O que fazer:** confirme se o fallback é adequado para o ambiente atual.

---

### `[NO VALUE]` — Atenção (`log.warn`)

**O que é:** a propriedade existe no YAML mas está sem valor (null ou vazio), e não há variável de ambiente correspondente em nenhuma fonte.

A propriedade terá valor `null` em runtime. Componentes Spring que dependam dela podem falhar.

**O que fazer:** adicione um valor no YAML ou defina a variável correspondente no SO.

---

### `[UNUSED]` — Informativo (`log.warn`)

**O que é:** a variável está disponível em alguma fonte mas não corresponde a nenhuma propriedade conhecida da aplicação.

Possíveis causas: typo no nome, propriedade removida do YAML, variável de infraestrutura não relacionada.

No modo padrão `collapse`, as variáveis de infraestrutura são separadas para a linha `[SYSTEM]` — então o `[UNUSED]` fica reservado para os casos realmente acionáveis (tipicamente typos ou propriedades removidas).

**O que fazer:** verifique se o nome está correto ou se a variável pode ser removida do ambiente.

---

### `[SYSTEM]` — Informativo (`log.info`)

**O que é:** variáveis de infraestrutura (S.O., JVM, Maven/Gradle, Kubernetes, runners de CI) presentes no ambiente mas sem correspondência com propriedades da aplicação. São colapsadas em uma única linha para não poluir o relatório.

Aparece apenas no modo `env.governance.system-var-mode: collapse` (padrão). A lista completa fica disponível ativando o log DEBUG.

**O que fazer:** normalmente nada — é ruído esperado. Se uma variável da sua aplicação aparecer aqui por engano, verifique a lista curada; se for específica do seu ambiente/CI, adicione o padrão em `env.governance.system-var-patterns`.

---

### Cenário: mesma propriedade, dois nomes de variável

O caso mais comum de confusão: um placeholder cujo nome difere do que o relaxed binding geraria para a chave. Ambos são registrados e qualquer um funciona para sobrescrever o valor.

```yaml
# application.yml
bridge:
  poller:
    max-messages-per-poll: ${BRIDGE_MAX_MESSAGES_PER_POLL:100}
```

```bash
# Opção 1 — sobrescreve via relaxed binding (nome derivado da chave)
BRIDGE_POLLER_MAX_MESSAGES_PER_POLL=200

# Opção 2 — sobrescreve via placeholder (nome explícito no YAML)
BRIDGE_MAX_MESSAGES_PER_POLL=200
```

> Quando `BRIDGE_POLLER_MAX_MESSAGES_PER_POLL` está definida no SO, ela sobrescreve a propriedade inteira via relaxed binding antes de o Spring avaliar o placeholder interno. Nesse caso, `[FALLBACK]` para `BRIDGE_MAX_MESSAGES_PER_POLL` no INFO é esperado e pode ser ignorado.
