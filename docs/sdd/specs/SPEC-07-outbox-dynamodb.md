# SPEC-07 — Outbox transacional no DynamoDB

| | |
|---|---|
| **Depende de** | SPEC-03 (portas de saída, aspecto de métricas), SPEC-05 (perfis, Actuator), SPEC-06 (mapeamento porta→AWS) |
| **Habilita** | Passos 4 e 5 do caminho de migração incremental do RFC-0001 (consumidores Kafka por time, Saga coordinator) — nenhum dos dois é código desta entrega |
| **Decisões aplicáveis** | D-07 (nova); supera D-01, D-02 |
| **Altera comportamento observável?** | **Sim**: a resposta HTTP não depende mais da disponibilidade de Estoque/Registro/Entrega/Financeiro; passa a depender da disponibilidade do DynamoDB |
| **Constituição** | [P4](../CONSTITUICAO.md#p4--executável-localmente-sem-aws-real) (revisado nesta entrega — ver nota abaixo) |

## Objetivo

Corrigir duas desconformidades entre o código entregue até SPEC-06 e o desenho de [RFC-0001](../../rfc/RFC-0001-arquitetura-produtiva-aws.md)/[ADR-0001](../../adr/ADR-0001-notificacoes-assincronas-saga-orquestrada.md): (1) a ausência de qualquer persistência (RFC-0001 já apontava isso como lacuna), e (2) a permanência das quatro chamadas síncronas a Estoque/Registro/Entrega/Financeiro dentro deste serviço, quando o desenho já as trata como consumidores Kafka independentes, fora deste código-fonte.

## Contexto verificado

Até SPEC-06, `GerarNotaFiscalService.notificar` disparava `EstoqueNotificacaoPort`, `RegistroNotificacaoPort`, `EntregaNotificacaoPort` e `FinanceiroNotificacaoPort` em paralelo (D-01) e agregava falhas (D-02) — mas a resposta HTTP continuava esperando as quatro, e nada era persistido. RFC-0001/ADR-0001 já descreviam o desenho-alvo (outbox no DynamoDB, evento no Kafka, Saga coordinator), mas nenhuma parte dele existia em código; os documentos tratavam isso, corretamente até aqui, como proposta arquitetural.

Esta entrega implementa o **primeiro passo** do caminho de migração incremental do RFC (item 3, adiantando a remoção do item 4 — ver "Desvio deliberado do RFC" abaixo): o outbox no DynamoDB. O passo seguinte — DynamoDB Streams alimentando uma Lambda que publica em `notafiscal.emitida.v1` — é infraestrutura AWS real (Lambda + Streams), não código Spring Boot deste repositório, e por isso não está nesta entrega.

### Desvio deliberado do RFC: sem dupla-escrita

O RFC descreve o passo 3 como "introduzir o outbox **mantendo** as chamadas síncronas atuais em paralelo, para validar consumidores novos sem risco". Esta entrega vai direto ao estado-alvo — outbox sim, quatro chamadas síncronas removidas — sem o período de dupla-escrita. Justificativa: os "consumidores novos" que a dupla-escrita protegeria são, por definição, times fora deste repositório; não há como este serviço validá-los de forma incremental sem a Lambda relay e os quatro consumer groups, que também estão fora de escopo aqui. Manter as quatro chamadas síncronas só para depois removê-las em uma entrega futura violaria P1 (nada é escrito contra um estado intermediário que já se sabe que será removido).

### Nota sobre P4

P4 exige execução local sem depender de **recursos AWS reais**. Esta entrega introduz uma dependência local de **DynamoDB Local** (contêiner `amazon/dynamodb-local`, via `docker-compose.yml`) — não AWS real, não exige credencial, roda inteiramente na máquina do desenvolvedor. `./mvnw spring-boot:run` sem o compose ainda sobe a aplicação; a chamada ao endpoint de negócio falha com `500` se não houver um DynamoDB Local acessível em `app.outbox.dynamodb.endpoint-override`, exatamente como qualquer outra dependência de infraestrutura declarada (compare com o Postgres de um serviço com persistência relacional). P4 permanece satisfeito na letra ("sem AWS real"); o texto do princípio não previa persistência quando foi escrito, porque até SPEC-06 a aplicação não persistia nada.

## Requisitos

**REQ-7.1 — As quatro notificações síncronas são removidas, não substituídas por outras quatro.**
`EstoqueNotificacaoPort`, `RegistroNotificacaoPort`, `EntregaNotificacaoPort`, `EntregaIntegrationPort`, `FinanceiroNotificacaoPort` e seus adapters deixam de existir. Nenhum novo adapter os substitui um a um — a saída do serviço converge para uma única escrita no outbox.
*Verificação:* `grep -rn "EstoqueNotificacaoPort\|RegistroNotificacaoPort\|EntregaNotificacaoPort\|FinanceiroNotificacaoPort" src/main/java` vazio.

**REQ-7.2 — Outbox transacional: estado + evento no mesmo item.**
Ao calcular a `NotaFiscal`, o serviço grava um item no DynamoDB com `idNotaFiscal` (chave), `payloadEvento` (a `NotaFiscal` serializada em JSON), `status=PENDENTE` e `criadoEm` — antes de responder ao chamador.
*Verificação:* `DynamoDbNotaFiscalProcessamentoAdapterTest` (Testcontainers, DynamoDB Local real).

**REQ-7.3 — Este serviço só escreve; não publica no Kafka.**
Não há `KafkaTemplate`, tópico ou publisher neste código. A publicação a partir do DynamoDB Streams é uma Lambda fora deste repositório (RFC-0001).
*Verificação:* nenhuma dependência `org.springframework.kafka`/`org.apache.kafka` em `pom.xml`.

**REQ-7.4 — Contrato HTTP inalterado (P2).**
`POST /api/pedido/gerarNotaFiscal` continua com o mesmo request/response; a falha ao gravar o outbox responde `500` (mesma família de erro que uma falha de notificação respondia antes).
*Verificação:* suítes de SPEC-02/SPEC-03 (`GerarNotaFiscalCaracterizacaoTest`, `GerarNotaFiscalContratoTest`) passam sem edição de contrato.

**REQ-7.5 — Local sem AWS real (P4, revisado).**
Perfil `local` aponta para um DynamoDB Local via `docker-compose.yml`, sem credencial — `./mvnw spring-boot:run` e o contêiner da aplicação funcionam de forma idêntica, apontando para o mesmo DynamoDB Local.
*Verificação:* `docker compose up` sobe `dynamodb-local` + aplicação; `POST /api/pedido/gerarNotaFiscal` responde `200`.

**REQ-7.6 — Métrica por adaptador de saída continua válida (REQ-5.4).**
O aspecto único de SPEC-05 (`MetricasAdaptadorSaidaAspect`) instrumenta o novo (e único) adaptador de saída sem nenhuma mudança nele.
*Verificação:* `ObservabilidadeTest` — `integracao_downstream_seconds_count{adapter="DynamoDbNotaFiscalProcessamentoAdapter"}`.

## Desenho

### Portas e adaptadores

```
application/
├── outbox/
│   ├── NotaFiscalProcessamento      (idNotaFiscal, payloadEvento, status, criadoEm)
│   └── StatusProcessamento          (PENDENTE — só isso; PROCESSADA/FALHOU são do Saga, fora de escopo)
├── port/out/
│   └── NotaFiscalProcessamentoRepositoryPort   (salvar — só escrita)
└── config/
    └── DynamoDbConfig                (bean DynamoDbClient; endpoint-override vazio = AWS real)

adapter/out/outbox/
├── DynamoDbNotaFiscalProcessamentoAdapter   (PutItem)
└── OutboxTableInitializer                   (ApplicationRunner; cria a tabela só se auto-create-table=true)
```

`GerarNotaFiscalService` perde `estoquePort`/`registroPort`/`entregaPort`/`financeiroPort`/`notificacoesExecutor` e ganha `outboxRepository` + `ObjectMapper`. `ExecutorConfig` (o `Executor` de virtual threads de D-01) é removido — não há mais nada para paralelizar dentro da requisição.

### Por que uma tabela sem GSI

A tabela `nota_fiscal_processamento` tem só a chave primária (`idNotaFiscal`, `HASH`). Não há índice secundário por `status`: nada neste serviço lê a tabela — quem lê é a Lambda relay (via DynamoDB Streams, que entrega todo `PutItem`, sem precisar de índice) e, mais adiante, o Saga coordinator (fora de escopo). Adicionar uma GSI sem um leitor real neste código violaria a mesma disciplina de "nenhuma property/estrutura especulativa" que SPEC-05 já aplicava a properties (REQ-5.9).

### Por que ObjectMapper é `tools.jackson.databind.ObjectMapper`

Spring Boot 4.1.1 (SPEC-01) modularizou a serialização Jackson: `spring-boot-starter-web` traz Jackson 3 (`tools.jackson.core`/`tools.jackson.databind`) como o `ObjectMapper` **injetável** pelo container — não `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2, ainda presente no classpath só como dependência transitiva do `springdoc-openapi`, sem bean Spring associado). `NotaFiscal`/`Pedido` continuam usando `com.fasterxml.jackson.annotation.@JsonProperty` normalmente — Jackson 3 manteve o módulo de anotações no pacote clássico; só `jackson-core`/`jackson-databind` migraram de groupId. Os testes de contrato já usavam `tools.jackson.databind.ObjectMapper`/`JsonNode` antes desta spec; SPEC-07 apenas segue a mesma convenção ao injetar `ObjectMapper` em `application/`.

### DynamoDB local vs. produção — só a configuração muda

```properties
# application.properties (todos os perfis)
app.outbox.dynamodb.table-name=${DYNAMODB_TABLE_NAME:nota_fiscal_processamento}
app.outbox.dynamodb.region=${AWS_REGION:us-east-1}
app.outbox.dynamodb.endpoint-override=${DYNAMODB_ENDPOINT_OVERRIDE:}
app.outbox.dynamodb.auto-create-table=${DYNAMODB_AUTO_CREATE_TABLE:false}
```

`endpoint-override` vazio (dev/staging/prod) → `DynamoDbClient` resolve endpoint/credenciais pela cadeia padrão da AWS (task role do ECS, RFC-0001); `auto-create-table` fica `false` — a tabela real vem de IaC, fora deste serviço. `application-local.properties` liga os dois para o DynamoDB Local do compose. Nenhum código muda entre ambientes, só a property (mesmo padrão de SPEC-05).

### docker-compose

```yaml
dynamodb-local:
  image: amazon/dynamodb-local:latest
  command: ["-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb"]
  ports: ["8000:8000"]
```

Sem credencial, sem volume persistente (`-inMemory`) — dados não sobrevivem a `docker compose down`, aceitável para um outbox local de desenvolvimento.

## Plano de execução

- [x] Remover `EstoqueNotificacaoPort`, `RegistroNotificacaoPort`, `EntregaNotificacaoPort`, `EntregaIntegrationPort`, `FinanceiroNotificacaoPort` e os adapters em `adapter/out/{estoque,registro,entrega,financeiro}/`.
- [x] Remover `NotificacoesParcialmenteFalharamException` (não há mais falha parcial de "quatro chamadas" — há uma única escrita).
- [x] Remover `ExecutorConfig` (D-01 superada; nada para paralelizar).
- [x] Criar `NotaFiscalProcessamentoRepositoryPort` (só `salvar`), `NotaFiscalProcessamento`, `StatusProcessamento`.
- [x] Implementar `DynamoDbNotaFiscalProcessamentoAdapter` (PutItem) e `OutboxTableInitializer` (criação condicional da tabela).
- [x] Reescrever `GerarNotaFiscalService.notificar` como escrita síncrona no outbox, com `ObjectMapper` (`tools.jackson.databind`) para serializar o payload do evento.
- [x] `docker-compose.yml`: serviço `dynamodb-local`; app aponta para ele via env do compose.
- [x] `application*.properties`/`.env.example`: properties do outbox, sem credencial literal (REQ-5.6 continua valendo).
- [x] Testes: `GerarNotaFiscalServiceTest` reescrito com o outbox mockado; `DynamoDbNotaFiscalProcessamentoAdapterTest` novo, contra DynamoDB Local via Testcontainers; testes de MockMvc (`GerarNotaFiscalContratoTest`, `ObservabilidadeTest`, `GerarNotaFiscalCaracterizacaoTest`, `EndpointSegurancaTest`, `PedidoValidacaoTest`) ajustados — a maioria via `OutboxTestConfig` (stub em memória, já que não testam o adapter em si), `ObservabilidadeTest` via Testcontainers real (precisa do `PutItem` de fato acontecer para provar a métrica por adaptador).
- [x] `CONSTITUICAO.md`: D-01/D-02 marcadas como superadas; D-07 registrada.
- [x] `RFC-0001`/`ADR-0001`: seção de estado atual/migração atualizada para refletir o passo 3 entregue em código (ver diffs nesses documentos).

## Verificação

1. `./mvnw clean verify` verde, incluindo `DynamoDbNotaFiscalProcessamentoAdapterTest` (requer Docker disponível para Testcontainers, como já é o caso do `build-image`/smoke test de SPEC-06).
2. `docker compose up` sobe `dynamodb-local` + aplicação; `POST /api/pedido/gerarNotaFiscal` com qualquer payload de `src/main/resources/paylods/` responde `200` com o mesmo contrato de sempre.
3. `grep -rn "EstoqueNotificacaoPort\|RegistroNotificacaoPort\|EntregaNotificacaoPort\|FinanceiroNotificacaoPort\|EntregaIntegrationPort" src/main/java` vazio.
4. `grep -rn "kafka" pom.xml` (case-insensitive) vazio.
5. `GET :8081/actuator/prometheus` após uma requisição de sucesso contém `integracao_downstream_seconds_count{adapter="DynamoDbNotaFiscalProcessamentoAdapter",...,resultado="sucesso"}`.

## Fora de escopo

- Publicação no Kafka (DynamoDB Streams → Lambda) — infraestrutura AWS real, não Spring Boot; RFC-0001 passo 4.
- Saga coordinator, tópicos de outcome, compensação — ADR-0001 passo 5.
- `GET /api/pedido/{idNotaFiscal}/status` — depende do Saga coordinator para ter algo além de `PENDENTE` para expor; sem ele, o endpoint só devolveria o mesmo estado sempre.
- Migrar Estoque/Registro/Entrega/Financeiro para consumer groups Kafka — são serviços de outros times, fora deste repositório.
- IaC da tabela DynamoDB em produção (Terraform/CDK) — P4, RFC-0001 é proposta arquitetural.
