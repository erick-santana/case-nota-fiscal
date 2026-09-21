import http from 'k6/http';
import {check} from 'k6';
import {Trend} from 'k6/metrics';
import {pedidoComNItens} from './lib/payloads.js';

// REQ-3.2 / cenário A: pedidos de 1 item vs. >=6 itens devem ter p95 comparável — a diferença
// só pode vir do processamento extra de itens, nunca do +5s anômalo do bug antigo
// (EntregaIntegrationPort disparava Thread.sleep(5000) a partir de 6 itens).
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const latenciaUmItem = new Trend('latencia_1_item', true);
export const latenciaSeisItens = new Trend('latencia_6_itens', true);

export const options = {
    scenarios: {
        umItem: {executor: 'constant-vus', vus: 5, duration: '15s', exec: 'umItem'},
        seisItens: {executor: 'constant-vus', vus: 5, duration: '15s', exec: 'seisItens', startTime: '15s'},
    },
    thresholds: {
        // Faixas por ordem de grandeza (dominado pela integração mais lenta, ~500ms), nunca ms exatos.
        latencia_1_item: ['p(95)<1500'],
        latencia_6_itens: ['p(95)<1500'],
    },
};

export function umItem() {
    const res = http.post(`${BASE_URL}/api/pedido/gerarNotaFiscal`, JSON.stringify(pedidoComNItens(1)), {
        headers: {'Content-Type': 'application/json'},
    });
    check(res, {'status 200': (r) => r.status === 200});
    latenciaUmItem.add(res.timings.duration);
}

export function seisItens() {
    const res = http.post(`${BASE_URL}/api/pedido/gerarNotaFiscal`, JSON.stringify(pedidoComNItens(6)), {
        headers: {'Content-Type': 'application/json'},
    });
    check(res, {'status 200': (r) => r.status === 200});
    latenciaSeisItens.add(res.timings.duration);
}
