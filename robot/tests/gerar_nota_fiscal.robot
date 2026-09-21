*** Settings ***
Documentation     Suíte E2E de fumaça: gera uma nota fiscal de ponta a ponta para cada payload de exemplo.
Resource          ../resources/api.resource
Suite Setup       Aguardar Aplicacao Disponivel

*** Test Cases ***
Gerar Nota Fiscal Para Pessoa Fisica
    ${resp}=    Gerar Nota Fiscal A Partir Do Payload    teste-pf.json
    Should Be Equal As Numbers    ${resp.status_code}    200
    Length Should Be    ${resp.json()}[itens]    1

Gerar Nota Fiscal Para Pessoa Juridica Simples Nacional
    ${resp}=    Gerar Nota Fiscal A Partir Do Payload    teste-pj-simples.json
    Should Be Equal As Numbers    ${resp.status_code}    200
    Length Should Be    ${resp.json()}[itens]    1
