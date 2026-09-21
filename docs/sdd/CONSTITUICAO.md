# Constituição da entrega

> Documento normativo. Todas as especificações de [`specs/`](./specs/) herdam o que está aqui, e nenhuma pode contradizê-lo. Quem executa uma spec — pessoa ou agente — lê este arquivo primeiro.

---

## P1 — Nada é especificado contra um estado intermediário

Esta entrega é **um único estado final**: um branch, um conjunto de artefatos. As seis specs são *frentes de trabalho* da mesma solução, não incrementos publicáveis de forma independente. A ordem entre elas existe por segurança de refatoração, não por releases.

Consequência operacional, e o princípio que governa todo o resto:

> **Todo design é escrito contra o estado final.** Nenhuma spec introduz algo que outra spec vai remover, mover ou recalibrar.

Na prática: o `+5s` de latência anômala nunca é escrito no código novo (não há "correção", há ausência); o executor já nasce de virtual threads; os testes já nascem no pacote definitivo; o `ApiExceptionHandler` é criado por uma spec e estendido por outra, nunca duplicado.

## P2 — O contrato JSON é imutável

O payload de entrada e o de saída são congelados campo a campo. Nenhum `@JsonProperty` é adicionado, removido ou renomeado em `model/`. Qualquer constraint, validação ou instrumentação atua sobre o **valor** já desserializado, nunca sobre a forma do JSON.

## P3 — As latências simuladas são o cenário, não o problema

`Thread.sleep` nas integrações representa chamadas de rede reais e **não é removido nem reduzido**: 380ms (Estoque), 500ms (Registro), 150ms + 200ms (Entrega + integração externa), 250ms (Financeiro). Ganho de performance vem de concorrência, não de apagar latência.

A **única** exceção é a parcela anômala: `+5s` condicionada a `itens.size() > 5`. Ela não corresponde a nenhum custo real de uma chamada de agendamento e é desproporcional por construção.

## P4 — Executável localmente, sem AWS real

A aplicação roda com `./mvnw spring-boot:run` e via contêiner sem nenhuma credencial ou recurso AWS. Nenhuma infraestrutura é provisionada: [RFC-0001](../rfc/RFC-0001-arquitetura-produtiva-aws.md) é proposta arquitetural, não IaC aplicado.

## P5 — Um teste que muda junto com o código que vigia não prova nada

A rede de testes é ancorada no contrato HTTP — o único seam que sobrevive à refatoração. A suíte de caracterização de [SPEC-02](./specs/SPEC-02-rede-de-testes.md) precisa passar **sem nenhuma edição** antes e depois de [SPEC-03](./specs/SPEC-03-nucleo-alvo.md). A única exceção autorizada é o caso de D-03, alterado no mesmo commit que implementa a mudança.

---

## Decisões transversais

Cada uma foi tomada uma única vez e vale para a entrega inteira. Specs referenciam por ID; não repetem a justificativa.

### D-01 — As notificações são paralelizadas com virtual threads

O `Executor` das quatro notificações é `Executors.newVirtualThreadPerTaskExecutor()`. As chamadas são I/O bloqueante puro — a carga exata em que virtual threads dispensam dimensionar pool, que é o principal risco de um pool de plataforma aqui: cada requisição consome quatro tarefas, então um pool pequeno enfileira sob concorrência e a latência volta a somar, enquanto um pool grande desperdiça threads bloqueadas.

Aplicada em [SPEC-03](./specs/SPEC-03-nucleo-alvo.md#paralelização-com-virtual-threads-d-01). Justificativa de adoção de recurso Java 21 em [SPEC-01](./specs/SPEC-01-stack-e-base.md#recursos-de-java-21-adotados-nesta-entrega).

### D-02 — Falha parcial nas notificações mantém o comportamento observável atual

Com as quatro chamadas concorrentes, uma falha não impede que as outras três executem. A requisição continua respondendo `500` se qualquer uma falhar, e as quatro são aguardadas antes da resposta.

Deliberadamente **não** adiantamos o "responde 200 e notifica depois": isso só é seguro com o outbox e a saga do [ADR-0001](../adr/ADR-0001-notificacoes-assincronas-saga-orquestrada.md). Sem eles, seria trocar um erro visível por uma inconsistência silenciosa.

Consequência honesta e registrada: nesta entrega uma falha pode deixar efeitos aplicados em três sistemas e ausente no quarto. É exatamente o problema que a Saga do ADR-0001 resolve. Detalhe em [SPEC-03](./specs/SPEC-03-nucleo-alvo.md#falha-parcial-d-02).

### D-03 — `RegimeTributacaoPJ.OUTROS` passa a ser erro explícito

Hoje esse valor não casa em nenhum ramo e a nota sai com `itens: []`, sem erro. Emitir uma nota fiscal sem itens é pior do que recusar o pedido. Passa a responder **`422 Unprocessable Entity`**.

É a **única mudança deliberada de comportamento observável** desta entrega, e está coberta por teste. O caso caracterizado em SPEC-02 é atualizado no mesmo commit que implementa a mudança em SPEC-03 — nunca como "ajuste para o teste passar".

### D-04 — `valor_total_itens` passa a ser validado contra os itens

O campo dirige a escolha da faixa de alíquota e hoje é aceito sem conferência: enviar um total menor que `Σ(valor_unitario × quantidade)` reduz o imposto devido — evasão fiscal por API. Passa a ser validado com tolerância de arredondamento e rejeitado com `400`.

Endereça diretamente a inconsistência de "valor total calculado" relatada no README. Ambos os payloads de exemplo satisfazem a invariante (`50 × 2 = 100`; `730 × 8 = 5840`), o que indica que ela é real e não uma regra inventada aqui. Implementada em [SPEC-04](./specs/SPEC-04-seguranca-e-validacao.md#d-04--consistência-de-valor_total_itens).

### D-05 — `valorTributoItem` continua ignorando `quantidade`

`valorTributo = valorUnitario × aliquota` não multiplica pela quantidade. É provavelmente incorreto do ponto de vista fiscal, mas o README não o reporta como bug e alterá-lo mudaria o valor de toda nota emitida sem mandato de negócio.

**Preservado e travado por teste**, com a divergência registrada em [SPEC-02](./specs/SPEC-02-rede-de-testes.md#d-05--comportamento-fiscal-preservado-deliberadamente) para decisão do dono do produto. A assimetria com D-04 — que confere `valor_total_itens` contra `Σ(valor_unitario × quantidade)` — é mais um indício de que D-05 merece revisão de negócio.

### D-06 — Campos não mapeados dos payloads de exemplo permanecem não mapeados

`bairro`, `cidade` e `pais` chegam em `enderecos[]` e não existem em `Endereco.java`; são descartados e somem da resposta, já que o `Destinatario` de entrada é ecoado na nota. Mapeá-los acrescentaria três campos ao JSON de saída.

Mantemos o comportamento atual e o **travamos por teste de contrato**, registrando a pendência. O teste é o que impede que isso volte a passar despercebido.

---

## Restrições de escopo

| Restrição | Origem |
|---|---|
| Contrato JSON de `Pedido`/`NotaFiscal` preservado campo a campo | README do desafio (P2) |
| Latências simuladas preservadas; apenas o `+5s` anômalo é eliminado | README do desafio (P3) |
| Aplicação executável localmente sem recursos AWS reais | P4 |
| Nenhuma infraestrutura AWS provisionada | P4 |
| Nenhuma funcionalidade nova de produto — o escopo é bug, arquitetura, teste e entrega | README do desafio |
