export function pedidoComNItens(n) {
    const itens = [];
    for (let i = 1; i <= n; i++) {
        itens.push({id_item: String(i), descricao: `Item ${i}`, valor_unitario: 100.0, quantidade: 1});
    }
    return {
        id_pedido: 1,
        data: '2026-01-01',
        valor_total_itens: 100.0 * n,
        valor_frete: 10.0,
        itens,
        destinatario: {
            nome: 'Fixture k6',
            tipo_pessoa: 'FISICA',
            regime_tributacao: null,
            documentos: [{numero: '12345678900', tipo: 'CPF'}],
            enderecos: [{
                cep: '00000000', logradouro: 'Rua X', numero: '1', estado: 'SP',
                complemento: '', finalidade: 'ENTREGA', regiao: 'SUDESTE',
            }],
        },
    };
}
