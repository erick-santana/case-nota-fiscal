# Visão de Negócio — Gerador de Nota Fiscal

## O que a aplicação faz

Recebe um **pedido de venda** já fechado (itens, valores e dados do destinatário) e devolve a **nota fiscal** correspondente: quanto de imposto incide sobre cada item, quanto custa o frete até o destinatário e o pacote de dados que representa a nota emitida. É a peça que fecha uma venda do ponto de vista fiscal e logístico — depois que o cliente já comprou, esta aplicação calcula "quanto de imposto, quanto de frete, e dispara os avisos para o resto da empresa".

Fluxo de negócio, em uma frase: **pedido entra → imposto e frete são calculados → nota fiscal é montada → outras áreas da empresa (estoque, contábil, logística, financeiro) são avisadas para agir**.

## Entrada: o pedido

O pedido (`Pedido`) chega com o que já foi decidido na venda:

- **Itens comprados**: descrição, valor unitário e quantidade de cada produto.
- **Valor total dos itens** e **valor de frete "base"** (antes de ajustes regionais).
- **Destinatário**: quem vai receber a nota — nome, documentos (CPF/CNPJ), e os endereços cadastrados (pode haver mais de um, com finalidades diferentes: entrega, cobrança, ou ambos).
- **Perfil fiscal do destinatário**: se é uma **pessoa física** ou uma **pessoa jurídica** e, neste último caso, em qual **regime de tributação** ela está enquadrada (Simples Nacional, Lucro Real ou Lucro Presumido) — isso é o que a Receita Federal usa para determinar a alíquota de imposto aplicável.

Esse formato de entrada é um contrato fixo com quem consome a API: nenhuma mudança de negócio pode alterar os campos que chegam nesse payload.

## Regra de negócio 1: cálculo do imposto (alíquota)

Cada nota fiscal tem um imposto embutido, calculado por item. A alíquota (percentual de imposto) não é fixa — ela depende de **quem está comprando** e de **quanto está sendo comprado**:

- **Pessoa física**: alíquota cresce em degraus conforme o valor total dos itens do pedido — de isento (compras pequenas) até a faixa mais alta para compras de maior valor.
- **Pessoa jurídica**: a régua de faixas de valor é a mesma ideia, mas os percentuais mudam de acordo com o **regime tributário** da empresa destinatária (Simples Nacional, Lucro Real ou Lucro Presumido têm cada um sua própria tabela de alíquotas), porque cada regime tem uma carga tributária diferente prevista em lei.

Ou seja: o imposto de uma nota fiscal não é um número fixo por produto — é uma função de "quem compra, sob qual regime, e quanto está sendo comprado no total". Essa é a regra de negócio mais sensível do sistema, porque erros aqui geram valor de imposto incorreto na nota — um problema fiscal e não só técnico.

## Regra de negócio 2: cálculo do frete

O valor de frete informado no pedido não é o valor final cobrado — ele sofre um **acréscimo percentual conforme a região do país** para onde a entrega vai (Norte, Nordeste, Centro-Oeste, Sudeste ou Sul), refletindo o custo logístico real de entregar em cada região (regiões mais distantes/de acesso mais caro têm um percentual de acréscimo maior).

A região usada para esse cálculo é sempre a do endereço marcado como destino da entrega (ou de cobrança-e-entrega, quando o cliente usa o mesmo endereço para as duas finalidades) — não qualquer endereço cadastrado.

## Saída: a nota fiscal

O resultado (`NotaFiscal`) é o documento fiscal pronto: um identificador único, data/hora de emissão, o valor total dos itens, o valor de frete já com o acréscimo regional aplicado, a lista de itens com o imposto calculado por item, e os dados do destinatário. É esse documento que os sistemas consumidores usam como fonte de verdade da venda.

## O que acontece depois de calcular a nota: avisos para o resto da empresa

Depois que a nota é montada, o sistema notifica quatro áreas/sistemas da empresa de que a venda aconteceu e precisa ser processada por cada uma:

1. **Estoque** — dar baixa nos itens vendidos (o produto sai do estoque disponível).
2. **Registro fiscal/contábil** — registrar formalmente a nota fiscal emitida.
3. **Entrega/logística** — agendar o envio físico da mercadoria ao destinatário.
4. **Financeiro** — lançar o valor da nota em contas a receber, para cobrança.

Cada uma dessas é tratada como uma integração com um sistema externo real (não é um cálculo interno) — o que explica por que cada uma tem um tempo de resposta associado: é o tempo que, na vida real, levaria para aquele sistema confirmar o recebimento da informação. Uma venda só está "processada de ponta a ponta" quando as quatro confirmações acontecem.

## Por que isso importa para o negócio

- **Consistência fiscal**: se o cálculo de imposto ou de frete estiver errado, a nota emitida diverge do que deveria ser cobrado — isso é um problema de compliance fiscal, não apenas um "bug de sistema".
- **Isolamento entre vendas**: cada pedido processado precisa gerar uma nota fiscal independente. Se dados de um pedido anterior vazarem para a nota de um pedido novo (itens ou valores errados aparecendo em outra nota), isso é uma inconsistência que pode gerar cobrança errada, nota fiscal divergente do que foi vendido, e problemas com fiscalização.
- **Tempo de resposta**: cada venda só é considerada concluída quando as quatro áreas (estoque, registro, entrega, financeiro) forem notificadas. Pedidos com mais itens (compras maiores) não deveriam demorar desproporcionalmente mais para processar — isso afeta a experiência de quem depende dessa confirmação (loja, marketplace, canal de venda) para liberar a compra ao cliente final.

## Fora do escopo desta aplicação

- Não há emissão fiscal real perante a Receita Federal/SEFAZ (não gera XML de NF-e, não integra com SEFAZ) — o nome "nota fiscal" aqui se refere ao documento de saída calculado pela aplicação, não a uma nota fiscal eletrônica homologada.
- Não há persistência de dados: cada requisição é processada e devolvida sem guardar histórico de pedidos ou notas emitidas.
- As integrações com estoque, registro, entrega e financeiro são simuladas (representam a latência esperada de chamadas reais), não conexões com sistemas reais dessas áreas.
