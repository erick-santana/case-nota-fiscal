# SPEC-05 — Observabilidade e configuração por ambiente

| | |
|---|---|
| **Depende de** | SPEC-01 (stack), SPEC-03 (portas de saída, para o aspecto de métricas) |
| **Paralela a** | [SPEC-04](./SPEC-04-seguranca-e-validacao.md) — camadas diferentes, sem colisão |
| **Habilita** | SPEC-06 (health check para o smoke test; perfis para a matriz de promoção) |
| **Decisões aplicáveis** | define o conjunto canônico de perfis, referenciado por SPEC-04 e SPEC-06 |
| **Altera comportamento observável?** | Não — a instrumentação é transversal e não toca o payload |
| **Constituição** | [P2](../CONSTITUICAO.md#p2--o-contrato-json-é-imutável), [P4](../CONSTITUICAO.md#p4--executável-localmente-sem-aws-real) |

> **Nota de revisão ([SPEC-07](./SPEC-07-outbox-dynamodb.md))**: os quatro adaptadores simulados citados abaixo (`EstoqueAdapter`, `RegistroAdapter`, `EntregaAdapter`+`EntregaIntegrationAdapter`, `FinanceiroAdapter`) foram removidos; o aspecto único (`MetricasAdaptadorSaidaAspect`) hoje instrumenta um único adaptador real, `DynamoDbNotaFiscalProcessamentoAdapter`. O texto abaixo é histórico.

## Objetivo

Entregar métricas, logs estruturados, health checks e configuração externalizada por ambiente, para que o serviço seja monitorável em produção e implantável na AWS sem perder a execução local sem fricção (P4).

## Contexto verificado

- `pom.xml` não tem `spring-boot-starter-actuator` nem `micrometer-registry-prometheus`.
- `src/main/resources/application.properties` já tem `spring.profiles.default=local` (adicionado por SPEC-04); nenhum outro profile existe ainda.
- `GeradorNotaFiscalApplication` tem 13 linhas: `@SpringBootApplication` + `SpringApplication.run`. Nenhuma classe do projeto lê `@Value`, `@ConfigurationProperties` ou `System.getenv()`.
- Não há `logback-spring.xml`, `Dockerfile`, `docker-compose.yml` nem `.github/`.
- `.gitignore` não ignora `.env`.
- `pom.xml:44-53` **já** configura o `spring-boot-maven-plugin` com `paketobuildpacks/builder-jammy-base:latest` — ou seja, `./mvnw spring-boot:build-image` já produz uma imagem OCI hoje, sem `Dockerfile`.

Hoje é impossível saber se a aplicação está de pé sem fazer uma chamada de negócio completa, e as quatro integrações retornam `void` sem expor sinal algum ao chamador.

## Requisitos

**REQ-5.1 — Actuator em porta de management separada.**
`/actuator/*` não é alcançável pela porta da aplicação.
*Verificação:* `GET :8080/actuator/health` falha; `GET :8081/actuator/health` responde.

**REQ-5.2 — Probes de liveness/readiness compatíveis com orquestração em contêiner.**
Usadas pelo healthcheck do compose e, no desenho de SPEC-06, pelo target group do ALB.

**REQ-5.3 — Métricas de fluxo.**
Tempo de resposta, volume de itens processados, contadores de sucesso e falha, disponíveis via Micrometer/Prometheus.

**REQ-5.4 — Métricas por adaptador de saída, via aspecto único.**
Tempo e falha por porta de saída — não quatro trechos duplicados.

**REQ-5.5 — Log estruturado com correlação, sem dado pessoal.**
JSON com `idNotaFiscal`/`idPedido` em MDC. **Nenhum log emite o payload completo de `Pedido`/`Destinatario`; `Documento.numero` é mascarado onde aparecer.**
*Verificação:* asserção sobre a saída do appender, não inspeção manual.

**REQ-5.6 — Perfis canônicos e configuração externalizada.**
`local`/`dev`/`staging`/`prod` (mais `test`, usado só pela suíte), sem credencial ou endpoint literal em properties versionadas.
*Verificação:* nenhum literal `*.amazonaws.com`, `arn:aws:` ou `AKIA` em `application*.properties`.

**REQ-5.7 — Paridade entre execução local e contêiner.**
`./mvnw spring-boot:run` e a imagem produzem o mesmo comportamento para o mesmo payload e perfil.

**REQ-5.8 — Stack local de observabilidade funcional.**
`docker compose up` sobe aplicação + Prometheus + Grafana, com datasource e dashboard provisionados por arquivo, sem exigir credenciais AWS reais (P4).

**REQ-5.9 — Nenhuma property especulativa.**
Nada de configuração sem leitor no código.

## Desenho

### Actuator em porta de management separada

```properties
management.server.port=8081
management.endpoints.web.exposure.include=health,prometheus,info
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=never
management.health.livenessstate.enabled=true
management.health.readinessstate.enabled=true
management.prometheus.metrics.export.enabled=true
management.metrics.tags.application=${spring.application.name}
```

A porta separada resolve, sem exceção de rota na API, quem pode raspar métricas. Expor `/actuator/prometheus` na mesma porta do endpoint de negócio obrigaria a escolher entre furar a cadeia de segurança com um `permitAll` de rota ou exigir JWT do scraper. Com `management.server.port`, o controle passa a ser de rede: em produção a 8081 só é alcançável pelo *security group* do scraper e pelo health check do target group; localmente, pelo contêiner do Prometheus na rede do compose. A cadeia de [SPEC-04](./SPEC-04-seguranca-e-validacao.md) registra um chain dedicado com `EndpointRequest.toAnyEndpoint()` e deixa o isolamento de rede ser o controle real.

> Os nomes acima são os da linha Spring Boot 3+/4, entregue em [SPEC-01](./SPEC-01-stack-e-base.md). A precisão importa porque as convenções mudaram e não são intercambiáveis: `management.prometheus.metrics.export.*` chamava-se `management.metrics.export.prometheus.*` até a 2.x, e `management.endpoint.health.probes.enabled` era `management.health.probes.enabled`.

### Métricas

**No fluxo**, a partir do caso de uso de SPEC-03:

| Métrica | Tipo | Para quê |
|---|---|---|
| `nota_fiscal_geracao_seconds` | Timer | Tempo de resposta do endpoint |
| `nota_fiscal_itens_processados` | DistributionSummary | Volume de itens por nota — e sinal de regressão se o vazamento de estado reaparecesse, porque a contagem cresceria de forma anômala entre chamadas |
| `nota_fiscal_geradas_total` / `nota_fiscal_falhas_total` | Counter | Volume e taxa de erro |

**Por adaptador de saída**, com um único `@Aspect` sobre as implementações das portas de `application/port/out/`:

```java
@Aspect
@Component
public class MetricasAdaptadorSaidaAspect {

    @Around("within(br.com.itau.geradornotafiscal.adapter.out..*)")
    public Object medir(ProceedingJoinPoint pjp) throws Throwable {
        Timer.Sample amostra = Timer.start(registry);
        String adapter = pjp.getTarget().getClass().getSimpleName();
        try {
            Object resultado = pjp.proceed();
            amostra.stop(registry.timer("integracao_downstream_seconds", "adapter", adapter, "resultado", "sucesso"));
            return resultado;
        } catch (Throwable t) {
            amostra.stop(registry.timer("integracao_downstream_seconds", "adapter", adapter, "resultado", "falha"));
            throw t;
        }
    }
}
```

Isso responde a pergunta que hoje é impossível responder — *qual das quatro integrações está lenta ou falhando* — e é precisamente o que as portas de SPEC-03 tornaram possível escrever uma vez só.

### Logging estruturado — e o que não pode entrar nele

`logback-spring.xml` com encoder JSON, `idNotaFiscal` e `idPedido` em MDC ao redor do processamento de cada requisição, de modo que toda linha de log de uma requisição seja correlacionável — e, em produção, casável com o trace de X-Ray por `idNotaFiscal` (RFC-0001).

Regra explícita: **nenhum log emite o payload completo de `Pedido` ou `Destinatario`, e `Documento.numero` é mascarado onde aparecer.** O payload carrega CPF/CNPJ, nome e endereço completo do destinatário. O perfil `local` liga `DEBUG` no pacote da aplicação, e o mesmo `logback-spring.xml` vai para produção — logar o objeto inteiro é exposição de dado pessoal (LGPD), não detalhe estético. O MDC fica restrito a identificadores técnicos.

### Configuração por ambiente

Perfis canônicos: **`local`, `dev`, `staging`, `prod`** — mais `test`, usado só pela suíte. O conjunto é definido aqui e referenciado por [SPEC-04](./SPEC-04-seguranca-e-validacao.md) (cadeia de segurança) e [SPEC-06](./SPEC-06-entrega-e-arquitetura.md) (matriz de promoção), em vez de cada documento manter a própria lista — um perfil que exista na matriz de deploy e não entre os profiles teria configuração e postura de segurança indefinidas.

`application.properties` — apenas o comum e não sensível:

```properties
spring.application.name=geradornotafiscal
spring.profiles.default=local
server.port=${SERVER_PORT:8080}
management.server.port=${MANAGEMENT_PORT:8081}
```

`application-local.properties` — nenhuma credencial, nenhum recurso AWS:

```properties
logging.level.br.com.itau.geradornotafiscal=DEBUG
logging.level.root=INFO
```

`application-dev`/`staging`/`prod.properties` — apenas nomes resolvidos em runtime:

```properties
logging.level.br.com.itau.geradornotafiscal=INFO
spring.security.oauth2.resourceserver.jwt.issuer-uri=${OIDC_ISSUER_URI}
```

Não existe property de seleção de implementação de integração: nesta entrega os quatro adaptadores simulados são os únicos que existem, em todos os perfis. Se e quando um adaptador AWS real surgir, a seleção é `@ConditionalOnProperty`/`@Profile` sobre o bean do adaptador — decidida com o adaptador na mão, não antecipada por uma property sem leitor (REQ-5.9).

Segredos: variável de ambiente em todos os ambientes — de `.env` localmente, da *task definition* do ECS a partir de Secrets Manager/Parameter Store em produção. Mesmo mecanismo, origem diferente, sem lógica duplicada. Hoje nenhuma integração exige segredo real (são `Thread.sleep`); o mecanismo está documentado para quando exigir.

### Containerização

**Buildpacks, sem `Dockerfile`.** O plugin já está configurado (`pom.xml:44-53`) e é o caminho oficial do `spring-boot-maven-plugin`. Um `Dockerfile` manual criaria uma segunda fonte de verdade para o build de imagem, com risco de divergirem (alguém atualiza o builder no `pom.xml` e esquece a imagem base do `Dockerfile`). Nenhum requisito atual — pacote de SO extra, processo adicional no contêiner — exige o controle de baixo nível que só um `Dockerfile` daria. Revisitar apenas se surgir um requisito concreto inexprimível por variáveis `BP_*`.

Consequência: o compose **consome** a imagem já construída, não a constrói.

```yaml
services:
  geradornotafiscal:
    image: geradornotafiscal:${APP_VERSION:-0.0.1-SNAPSHOT}
    env_file: [.env]
    ports:
      - "${SERVER_PORT:-8080}:8080"
      - "${MANAGEMENT_PORT:-8081}:8081"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health/readiness"]
      interval: 10s
      retries: 6

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./docker/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports: ["9090:9090"]

  grafana:
    image: grafana/grafana:latest
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
    volumes:
      - ./docker/grafana/provisioning:/etc/grafana/provisioning:ro
    ports: ["3000:3000"]
```

Prometheus e Grafana entram **ativos**, com datasource e dashboard provisionados por arquivo em `docker/grafana/provisioning/` — versionados, não montados à mão pela UI. O dashboard cobre as métricas da seção anterior: tempo de resposta (`p95`/`p99`), taxa de erro, volume de itens e tempo por adaptador de saída.

Pré-requisitos documentados explicitamente, não assumidos:

```bash
cp .env.example .env
./mvnw spring-boot:build-image
docker compose up
```

`.env.example` versionado, `.env` adicionado ao `.gitignore` **nesta spec**, antes de qualquer `.env` real existir localmente.

## Plano de execução

- [x] Adicionar `spring-boot-starter-actuator` e `micrometer-registry-prometheus`. Desvio registrado: também foi necessário `spring-boot-starter-aspectj` (não previsto no plano original) — ver nota abaixo.
- [x] Expor Actuator em **porta de management separada** (`management.server.port`), restrita por rede/SG.
- [x] Configurar probes de liveness/readiness compatíveis com ALB/ECS, com os nomes de propriedade da versão adotada em SPEC-01. Confirmado por inspeção do jar `spring-boot-health-4.1.1` (o módulo de health foi desmembrado do `spring-boot-actuator` nessa versão): `management.endpoint.health.probes.enabled`, `management.health.livenessstate.enabled`, `management.health.readinessstate.enabled` mantidos da 3.x.
- [x] Instrumentar tempo de resposta do endpoint, contador de falhas e volume de itens processados. Feito diretamente em `GerarNotaFiscalService` (não via aspecto) — REQ-5.3 não exige um mecanismo específico, e um segundo aspecto só para o caso de uso seria abstração sem uso real, já que há um único ponto de entrada.
- [x] Instrumentar tempo/falha **por adaptador de saída**, via um `@Aspect` único sobre as portas (`MetricasAdaptadorSaidaAspect`, em `application/observabilidade/`, fora de `adapter/out/` para não instrumentar a si mesmo).
- [x] Logging estruturado JSON com `idNotaFiscal`/`idPedido` em MDC. Desvio registrado: sem `logback-spring.xml` — ver nota abaixo.
- [x] Garantir que nenhum log emite o payload completo; mascarar `Documento.numero`. Regra de LGPD registrada e coberta por teste (`ObservabilidadeTest`, asserção sobre `ILoggingEvent` capturado por `ListAppender`, não sobre a formatação JSON).
- [x] Criar `application.properties` + `application-{local,dev,staging,prod}.properties`, sem credencial ou endpoint literal.
- [x] Criar `docker-compose.yml` subindo aplicação + Prometheus + Grafana (ativos, não comentados), `.env.example`, e adicionar `.env` ao `.gitignore`. Desvio registrado: healthcheck sem `curl` — ver nota abaixo.
- [x] Provisionar datasource e dashboard do Grafana por arquivo em `docker/grafana/provisioning/`.
- [x] Documentar o pré-requisito `./mvnw spring-boot:build-image` e `cp .env.example .env` antes de `docker compose up`.
- [x] Verificação estática: nenhum literal `*.amazonaws.com`, ARN ou `AKIA` em properties versionadas.

**Desvios em relação ao desenho literal desta spec, e por quê:**

1. **`spring-boot-starter-aop` não existe na 4.1.1** — a distribuição de starters do Boot 4 removeu esse artefato; `MetricasAdaptadorSaidaAspect` precisa de proxying via `@Aspect`, então a dependência usada é `spring-boot-starter-aspectj` (traz `spring-aop` e `aspectjweaver`; o `AopAutoConfiguration` do Boot habilita `@EnableAspectJAutoProxy` automaticamente ao detectar o weaver no classpath). Sem nenhuma delas o aspecto compila mas nunca intercepta nada — falha silenciosa, não de build.
2. **`EndpointRequest` mudou de pacote.** Em vez de `org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest` (3.x), a 4.1.1 move a classe para `org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest`, no módulo `spring-boot-security` — já presente transitivamente via `spring-boot-starter-oauth2-resource-server` (SPEC-04), sem dependência nova. `actuatorSecurityFilterChain` foi implementado em `SecurityConfig` (SPEC-04 já previa e deferiu esse bean para aqui).
3. **Sem `logback-spring.xml`.** A 4.1.1 traz *Structured Logging* nativo (`logging.structured.format.console=logstash`), que inclui o MDC como campos de topo do JSON sem nenhuma dependência extra (nem `logstash-logback-encoder`) e sem arquivo de configuração do Logback. Verificado empiricamente: uma requisição real produz `{"...","idPedido":"1","idNotaFiscal":"...",...}` na saída padrão. Escrever um `logback-spring.xml` manual para reimplementar o que o Boot já faz nativamente seria a mesma duplicação que P1 pede para evitar.
4. **`application-{dev,staging,prod}.properties` não declaram `spring.security.oauth2.resourceserver.jwt.issuer-uri`.** O esboço da seção "Desenho" mostrava essa property, mas `SecurityConfig.jwtDecoder` (SPEC-04) lê `OIDC_ISSUER_URI` diretamente via `@Value`, não essa property do Spring — declará-la violaria REQ-5.9 (nenhuma property sem leitor no código).
5. **`integracao_downstream_seconds` tem cinco valores de `adapter`, não quatro.** `EntregaAdapter.agendar()` chama `EntregaIntegrationPort.criarAgendamento()` (implementado por `EntregaIntegrationAdapter`, também em `adapter/out/entrega/`), e o pointcut único intercepta as duas chamadas. As quatro portas de notificação (`EstoqueAdapter`, `RegistroAdapter`, `EntregaAdapter`, `FinanceiroAdapter`) continuam todas presentes; o quinto valor (`EntregaIntegrationAdapter`) é uma granularidade extra e não invalida a verificação.
6. **Healthcheck do compose não usa `curl`.** A imagem Paketo `jammy-base` não inclui `curl` nem `wget`; testado via `docker exec`. O healthcheck usa `bash` (presente na imagem) com `/dev/tcp` para montar e ler uma requisição HTTP crua contra `/actuator/health/readiness` — validado de ponta a ponta com `docker compose up` real (container reportou `healthy`).

## Verificação

1. Contexto sobe com Actuator; `GET :8081/actuator/health/liveness` e `/readiness` → `200`, `{"status":"UP"}`.
2. Após uma chamada a `POST /api/pedido/gerarNotaFiscal`, `GET :8081/actuator/prometheus` → `200` contendo `nota_fiscal_geracao_seconds_count` e `integracao_downstream_seconds_count` com as quatro tags de `adapter`.
3. Nota com N itens → `nota_fiscal_itens_processados` reflete N.
4. Falha simulada em um adaptador → `integracao_downstream_seconds` com `resultado="falha"` para aquele adapter, e `nota_fiscal_falhas_total` incrementado.
5. `/actuator/*` não é alcançável pela porta 8080 da aplicação.
6. Log estruturado: uma requisição produz linhas JSON com `idNotaFiscal`/`idPedido` no MDC e **sem** `numero` de documento, nome ou endereço — asserção sobre a saída do appender.
7. Paridade `run` × contêiner: `./mvnw spring-boot:run` e a imagem com `SPRING_PROFILES_ACTIVE=local`, mesmo payload, mesma resposta.
8. `docker compose up` sobe aplicação, Prometheus e Grafana sem nenhuma variável AWS definida; o Prometheus tem o alvo `UP`.
9. Verificação estática (promovida a step de CI em SPEC-06): nenhum literal `*.amazonaws.com`, `arn:aws:` ou `AKIA` em `application*.properties` versionados.
10. Teste de contrato de SPEC-02 passa **sem edição** — a instrumentação é transversal e não toca o payload.

## Fora de escopo

- `Dockerfile` — a imagem continua vindo de `spring-boot:build-image` (decisão acima).
- Provisionar qualquer recurso AWS, ou exigir credencial real para rodar localmente (P4).
- Tracing distribuído com X-Ray: desenhado em RFC-0001, sem componente a instrumentar nesta entrega (chamada única, in-process).
- Alarmes e SLOs de produção — documentados em SPEC-06 e RFC-0001, não configurados.
- Property de seleção de adaptador real vs. simulado (REQ-5.9).
