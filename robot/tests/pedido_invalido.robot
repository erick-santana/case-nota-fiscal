*** Settings ***
Documentation     Bad cases derivados do contrato de entrada (Pedido) — SPEC-04. Cada caso é uma
...               variação mínima de um payload válido quebrando exatamente uma regra de
...               validação; complementa (não repete) a suíte de unidade/integração em src/test.
Resource          ../resources/api.resource
Suite Setup       Aguardar Aplicacao Disponivel

*** Test Cases ***
JSON Sintaticamente Invalido Retorna 400
    ${resp}=    Enviar Pedido Invalido    json_malformado.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Id Pedido Ausente Retorna 400
    ${resp}=    Enviar Pedido Invalido    sem_id_pedido.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Data Ausente Retorna 400
    ${resp}=    Enviar Pedido Invalido    sem_data.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Valor Total Itens Negativo Retorna 400
    ${resp}=    Enviar Pedido Invalido    valor_total_itens_negativo.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Valor Frete Negativo Retorna 400
    ${resp}=    Enviar Pedido Invalido    valor_frete_negativo.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Itens Vazio Retorna 400
    ${resp}=    Enviar Pedido Invalido    itens_vazio.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Itens Acima Do Limite Retorna 400
    ${resp}=    Enviar Pedido Invalido    itens_acima_do_limite.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Item Com Valor Unitario Negativo Retorna 400
    ${resp}=    Enviar Pedido Invalido    item_valor_unitario_negativo.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Item Com Quantidade Zero Retorna 400
    ${resp}=    Enviar Pedido Invalido    item_quantidade_zero.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Valor Total Itens Divergente Da Soma Dos Itens Retorna 400
    [Documentation]    D-04: valor_total_itens não pode divergir de Σ(valor_unitario × quantidade).
    ${resp}=    Enviar Pedido Invalido    valor_total_itens_divergente.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Destinatario Ausente Retorna 400
    ${resp}=    Enviar Pedido Invalido    destinatario_ausente.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Destinatario Sem Enderecos Retorna 400
    [Documentation]    REQ-4.9: regressão do NPE original — destinatario.enderecos ausente não
    ...                pode mais estourar 500.
    ${resp}=    Enviar Pedido Invalido    destinatario_sem_enderecos.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Destinatario Sem Documentos Retorna 400
    ${resp}=    Enviar Pedido Invalido    destinatario_sem_documentos.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Tipo Pessoa Ausente Retorna 400
    ${resp}=    Enviar Pedido Invalido    tipo_pessoa_ausente.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Juridica Sem Regime Tributacao Retorna 400
    ${resp}=    Enviar Pedido Invalido    juridica_sem_regime_tributacao.json
    Should Be Equal As Numbers    ${resp.status_code}    400

Corpo De Erro Nao Expoe Stack Trace Nem Valor Recebido
    [Documentation]    REQ-4.8, verificado de ponta a ponta com um dos bad cases acima.
    ${resp}=    Enviar Pedido Invalido    item_valor_unitario_negativo.json
    Should Be Equal As Numbers    ${resp.status_code}    400
    Corpo De Erro Deve Ser Seguro    ${resp}
