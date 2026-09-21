import http from 'k6/http';
import {check} from 'k6';
import {pedidoComNItens} from './lib/payloads.js';

// REQ-3.3 / cenário C: com as quatro notificações concorrentes sobre virtual threads, o tempo
// da requisição fica na ordem da chamada mais lenta (~500ms, Registro), não da soma sequencial
// das quatro (380+500+150+200+250 = ~1480ms). VU único para isolar o efeito de fila/rede.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    vus: 1,
    iterations: 30,
    thresholds: {
        // Limiar generoso por ordem de grandeza: descarta a soma sequencial sem ser flaky em CI.
        http_req_duration: ['p(95)<900', 'avg<900'],
    },
};

export default function () {
    const res = http.post(`${BASE_URL}/api/pedido/gerarNotaFiscal`, JSON.stringify(pedidoComNItens(1)), {
        headers: {'Content-Type': 'application/json'},
    });
    check(res, {'status 200': (r) => r.status === 200});
}
