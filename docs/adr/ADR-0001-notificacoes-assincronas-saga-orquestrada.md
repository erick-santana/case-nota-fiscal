# ADR-0001 — Notificações downstream assíncronas via eventos, com Saga orquestrada para compensação

## Status

Proposto. Revisado: backbone de eventos alterado de EventBridge + SQS para **Kafka/MSK**, por ser mais coerente com domínios de negócio sob ownership de times/contas distintas (ver "Alternativas consideradas").

## Contexto

`GeradorNotaFiscalServiceImpl.gerarNotaFiscal` calcula a `NotaFiscal` (imposto + frete) e, na mesma chamada HTTP, notifica quatro sistemas — Estoque, Registro fiscal/contábil, Entrega e Financeiro — de forma **síncrona e sequencial**, cada um com latência simulada (380ms, 500ms, 150ms+200ms, 250ms) representando uma chamada externa real. Isso soma ~1,3s por requisição no caminho feliz, e o bug documentado de `EntregaIntegrationPort` adiciona +5s quando a nota tem mais de 5 itens.

Além do custo de latência, o desenho atual acopla a disponibilidade da API de emissão de nota à disponibilidade das quatro integrações: se qualquer uma delas cair ou ficar lenta, a resposta HTTP inteira trava ou falha, mesmo que o cálculo fiscal — que é o que de fato define o contrato desta API — já tenha terminado.

A visão de negócio (`../VISAO-DE-NEGOCIO.md`) trata essas quatro notificações como uma transação de negócio que atravessa sistemas independentes: "uma venda só é considerada concluída quando as quatro áreas forem notificadas". Não existe transação distribuída viável entre esses quatro sistemas — a consistência só pode ser eventual, com compensação quando uma etapa falha depois que outras já confirmaram.

## Decisão

1. O cálculo da `NotaFiscal` permanece síncrono e no caminho da requisição (é rápido, determinístico, sem I/O externo, e é o que define o contrato da API).
2. Ao concluir o cálculo, o serviço persiste o estado da nota e publica um evento de domínio `NotaFiscalEmitida` usando o padrão **Transactional Outbox** (grava estado + evento atomicamente; um relay publica no barramento de eventos) — isso garante que o evento não se perde mesmo que o processo caia logo após responder ao cliente.
3. As quatro notificações passam a ser **consumidores assíncronos e independentes** desse evento, lendo do mesmo tópico **Kafka/MSK** (`notafiscal.emitida.v1`) via consumer groups próprios — cada um na conta AWS do time correspondente (Estoque, Registro, Entrega, Financeiro) — em vez de chamadas síncronas instanciadas com `new` dentro do serviço principal. O evento é particionado por `idNotaFiscal` (alta cardinalidade, distribuição uniforme) para evitar hot partitions; nunca por campos de baixa cardinalidade como `Regiao`/`TipoPessoa`, nem por `Documento.numero` isolado (risco de concentração em clientes de alto volume). Cada consumidor publica sua confirmação em seu próprio tópico de outcome (ex.: `estoque.baixa-confirmada.v1`) e tem seus próprios tópicos de retry/DLQ.
4. Um **Saga orquestrado**, agora Kafka-nativo (Kafka Streams ou um consumer Spring Kafka com state store/tabela de estado, em vez de AWS Step Functions), acompanha, por `idNotaFiscal`, as quatro confirmações — consumindo os 4 tópicos de outcome. Cada consumidor sinaliza seu resultado explicitamente nesse tópico (`status: CONFIRMADO|FALHOU`) — quem decide quando desistir (esgotou seus próprios retries/DLQ) é o próprio consumidor, não o coordenador. Se todas confirmarem, marca a nota como processada. Se uma publicar `FALHOU`, ou se nenhum outcome chegar dentro de um timeout (detectado por um job periódico, já que — diferente do Step Functions — o coordenador não tem timeout declarativo pronto), o orquestrador publica **comandos de compensação** (`notafiscal.compensacao.v1`) correspondentes às etapas que já haviam confirmado (ex.: estornar baixa de estoque se o lançamento financeiro falhar definitivamente) e emite um evento de falha. Detalhamento do contrato de outcome e do timeout em [RFC-0001](../rfc/RFC-0001-arquitetura-produtiva-aws.md#contrato-de-outcome-falha-e-timeout-da-saga).
5. O contrato JSON de `POST /api/pedido/gerarNotaFiscal` (request `Pedido` e response `NotaFiscal`) **não muda**. O status de processamento downstream é exposto por um recurso adicional (`GET /api/pedido/{idNotaFiscal}/status`), não pela alteração dos DTOs existentes.

Ver desenho completo de componentes, estratégia de particionamento e fluxo em [RFC-0001](../rfc/RFC-0001-arquitetura-produtiva-aws.md).

## Por que orquestração (Saga coordinator Kafka-nativo) e não coreografia pura

As quatro notificações são paralelas entre si — não há dependência sequencial de negócio entre elas (nenhuma precisa terminar antes da outra começar). Isso faz da orquestração a escolha mais direta, porque o valor que se busca aqui não é ordenar passos, e sim ter **um único lugar com o estado agregado** ("quantas das 4 confirmaram, qual falhou, o que precisa ser compensado"). Em coreografia pura, esse agregador teria que ser construído à parte de qualquer forma — a orquestração só torna isso explícito: o Saga coordinator consome os 4 tópicos de outcome e persiste o estado agregado (retry, timeout e decisão de compensação ficam no código do coordenador, não em uma ferramenta gerenciada separada como Step Functions, já que o backbone de eventos passou a ser o mesmo Kafka usado pelos 4 consumidores).

O domínio é sensível a auditoria (é um fluxo fiscal/contábil, ainda que simulado) — o estado persistido pelo Saga coordinator no DynamoDB (quais das 4 áreas confirmaram, quando, o que foi compensado) supre uma lacuna que hoje existe: a aplicação não persiste nada.

## Alternativas consideradas

- **Paralelizar as chamadas síncronas atuais (virtual threads/`CompletableFuture`)**: reduz a latência (soma → máximo das 4), mas não resolve o acoplamento de disponibilidade — uma integração fora do ar ainda derruba a resposta. Tratado como *passo intermediário* de baixo custo no caminho de migração (ver RFC-0001), não como solução final.
- **Fire-and-forget sem Saga** (publicar evento, nunca verificar as 4 confirmações): mais simples, mas contraria a visão de negócio, que define a conclusão da venda como a soma das quatro confirmações — sem rastreamento, uma falha silenciosa em uma das integrações (ex.: nota emitida mas nunca registrada fiscalmente) passaria despercebida.
- **Coreografia pura** (cada worker publica seu próprio evento de sucesso/falha e um serviço à parte decide compensação): descartada como principal por recriar de forma implícita o mesmo agregador de estado que a orquestração já oferece, com pior auditabilidade e mais lugares para a lógica de decisão vazar.
- **EventBridge + SQS (uma fila por consumidor) + AWS Step Functions como backbone, em vez de Kafka/MSK**: foi a escolha original deste ADR, sob a premissa de que os 4 consumidores eram "internos, fixos e conhecidos" — o shape que SQS+DLQ resolve nativamente sem a sobrecarga operacional de um cluster Kafka. Essa premissa não é realista em uma empresa de grande porte: é mais plausível que Estoque, Registro, Entrega e Financeiro sejam **domínios de negócio com ownership próprio** — times e, consequentemente, contas AWS distintas — do que consumidores internos fixos de um único time. Nesse cenário multi-domínio/multi-conta, Kafka/MSK como backbone padronizado é a escolha mais coerente do que EventBridge+SQS dedicado a este serviço, reaproveitando práticas de particionamento, schema registry e monitoramento já consolidadas para esse tipo de topologia. Revisado para Kafka/MSK — ver "Estratégia de particionamento" no [RFC-0001](../rfc/RFC-0001-arquitetura-produtiva-aws.md) para como o novo desenho evita hot partitions (chave de partição = `idNotaFiscal`, nunca campos de baixa cardinalidade como `Regiao`/`TipoPessoa`).

## Consequências

**Positivas**:
- A resposta da API deixa de depender da disponibilidade das quatro integrações — corrige a causa estrutural do problema de performance relatado (não só o bug pontual de `> 5 itens`).
- Cada integração escala, falha e é implantada de forma independente.
- Ganha-se trilha de auditoria de processamento (hoje inexistente, já que a aplicação não persiste nada).

**Negativas / custos assumidos**:
- O sistema passa a ser eventualmente consistente: é preciso um mecanismo para informar o canal de venda sobre falhas tardias (endpoint de status, webhook, ou evento que o canal assine) — algo que não era necessário no modelo síncrono.
- Mais peças de infraestrutura para operar e monitorar (tópicos MSK + Schema Registry, ACLs/IAM cross-account, 4 consumer groups, Saga coordinator, tabela DynamoDB de estado) do que a chamada direta atual.
- Compensações podem, elas mesmas, falhar — exige fila/alerta de intervenção manual como último recurso.
- Passa a ser necessário rastreamento distribuído (`idNotaFiscal` como correlação) para depurar um fluxo que antes era uma pilha de chamadas síncrona e local.
- Escolher mal a chave de partição (ex.: campo de baixa cardinalidade como `Regiao`) criaria hot partitions; a chave `idNotaFiscal` mitiga isso, mas exige disciplina desde a primeira versão do tópico, já que redimensionar partições depois de haver tráfego real é disruptivo.
- Sem Step Functions, o timeout de saga (consumidor que nunca publica nem sucesso nem falha) precisa ser implementado como código próprio (job periódico varrendo o DynamoDB), em vez de vir pronto de uma ferramenta gerenciada.

Essas contrapartidas são aceitas porque o volume de mudança é proporcional ao problema relatado: o sintoma (">6 itens = lento", "degradação com o tempo") tem raiz estrutural no acoplamento síncrono a integrações externas, e a visão de negócio já trata essas quatro notificações como uma transação distribuída — não como uma chamada simples que pode ser feita "fire-and-forget".
