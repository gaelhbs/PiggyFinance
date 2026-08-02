# Fluxo n8n — Agente com Tool-Calling — Design Spec

**Date:** 2026-08-02
**Status:** Approved
**Related spec:** `2026-08-02-n8n-ia-whatsapp-conversacional-design.md` (contrato backend — os 6 endpoints que este spec consome)

---

## Problem

O workflow n8n (`docs/App Pig-2.json`, ativo em produção) hoje só sabe fazer duas coisas: vincular WhatsApp via código `PIGGY-XXXXXX`, e tratar **toda** outra mensagem de texto/áudio como tentativa de transação. Uma pergunta como "quanto eu gastei esse mês?" cai no mesmo pipeline de extração de transação, que sempre tenta produzir uma transação — na falha, usa `amount: 0` / `description: "Não identificado"`.

O backend (PR #5) já expõe 6 endpoints novos para consulta/edição/exclusão via WhatsApp. Este spec cobre como o workflow n8n passa a usá-los.

---

## Solution Overview

Insere um **classificador de intenção** entre a extração de conteúdo (texto/áudio já transcrito) e o pipeline de criação de transação existente:

```
texto ─┐
       ├─→ [Classificar Intenção] ─→ "transacao" ─→ AI Agent AUDIO (pipeline atual, intocado)
audio ─┘                          └─→ "outro"     ─→ AI Agent Consulta (novo) ─→ Enviar texto
```

- **Pipeline de criação de transação existente não é alterado** — zero risco pro que já está em produção e ajustado.
- **AI Agent Consulta** (novo, tool-calling) ganha 6 tools — uma por endpoint novo do backend — e decide sozinho o que chamar com base na mensagem.
- Reaproveita o nó `Enviar texto` já existente para enviar a resposta (ele já espera `{{ $json.output }}`, formato padrão de saída de um Agent node do LangChain).

---

## New Nodes

### 1. `Classificar Intenção` — `@n8n/n8n-nodes-langchain.textClassifier`

- **Input:** `mensagem_usuario` (já normalizado pelos nós `texto`/`audio` existentes).
- **Categorias:**
  - `transacao`: "Mensagem descrevendo um gasto ou receita que a pessoa quer registrar (ex: 'gastei 50 no mercado', 'recebi salário')."
  - `outro`: "Qualquer outra coisa: perguntas sobre saldo/metas/plano, pedidos pra editar ou excluir o último lançamento, saudações, dúvidas gerais."
- **Modelo:** `gpt-4.1-mini` (mesmo padrão de custo dos agentes de extração existentes) — chat model node dedicado.
- Substitui as conexões diretas `texto → AI Agent AUDIO` e `audio → AI Agent AUDIO`: ambas passam a apontar pra este classificador; a saída `transacao` reconecta em `AI Agent AUDIO` (comportamento idêntico ao atual).

### 2. `AI Agent Consulta` — `@n8n/n8n-nodes-langchain.agent` (tool-calling)

- **Modelo:** `gpt-4.1-mini`, chat model node dedicado (novo, não reaproveita os existentes — isolamento).
- **Memória:** `memoryBufferWindow` novo, `sessionKey = {{ $('Webhook').item.json.body.data.key.remoteJid }}` — chaveado pelo contato do WhatsApp, com continuidade entre mensagens (diferente dos agentes existentes, que têm chaveamento de memória inconsistente — não corrigido aqui, ver "Out of Scope").
- **Tools acopladas:** as 6 abaixo. Nenhuma tool de criação de transação.
- **Saída:** texto final em `output`, consumido por `Enviar texto` (nó já existente, reaproveitado sem alteração).

---

## The 6 Tools

Todas: `n8n-nodes-base.httpRequestTool`, `On Error: Continue` (mesmo padrão já usado no nó `HTTP Vincular`) — assim, uma resposta de erro do backend (402/404/409) volta como observação da tool pro agente, em vez de derrubar a execução.

**Regra de segurança (todas as 6):** `phoneNumber` é sempre fixo — `{{ $('Edit Fields').item.json.phoneNumber }}` — nunca um parâmetro preenchido pela IA. Isso impede que uma injeção de prompt faça o agente consultar ou alterar a conta de outro número.

| Tool | Método/Endpoint | Parâmetros da IA |
|---|---|---|
| `consultar_saldo` | `GET /api/v1/transactions/whatsapp/summary` | `startDate`, `endDate` (opcionais) |
| `consultar_metas` | `GET /api/v1/goals/whatsapp` | nenhum |
| `consultar_status_plano` | `GET /api/v1/billing/whatsapp/status` | nenhum |
| `buscar_ultima_transacao` | `GET /api/v1/transactions/whatsapp/last` | nenhum |
| `editar_ultima_transacao` | `PATCH /api/v1/transactions/whatsapp/last` | `transactionId`, `description`, `amount`, `type`, `category` |
| `excluir_ultima_transacao` | `DELETE /api/v1/transactions/whatsapp/last` | `transactionId` (opcional — ver prompt) |

Todas as chamadas usam o header `X-Api-Key` já configurado nos nós HTTP existentes (mesma chave, mesmo domínio `https://piggy-repo-piggy-repo.moygyf.easypanel.host`).

O `transactionId` de editar/excluir é preenchido pela própria IA: como o Agent roda em loop (ReAct), ao chamar `buscar_ultima_transacao` antes, o `id` retornado fica disponível no contexto pra reusar na chamada seguinte. Isso ativa a proteção contra a condição de corrida corrigida no backend (PR #5, `409 TRANSACTION_MISMATCH`) — sem o id, o backend ainda funciona (comportamento antigo, sem a checagem), mas o prompt do agente instrui a sempre buscar antes de editar/excluir.

---

## Agent Prompt (AI Agent Consulta)

Conteúdo obrigatório do system prompt:

1. Papel: assistente financeiro do PiggyFinance no WhatsApp — mesma linha de voz do agente de confirmação existente (parágrafos curtos, emojis pertinentes, sem markdown, tom amigável).
2. Sempre chamar `buscar_ultima_transacao` antes de `editar_ultima_transacao`/`excluir_ultima_transacao`, e usar o `id` retornado.
3. Fora do escopo das tools (saudação, pergunta genérica não coberta): responder educadamente listando o que consegue fazer (saldo, metas, status do plano, editar/excluir último lançamento) — nunca inventar dado financeiro.
4. Tratamento de erro por `code` retornado pelas tools:
   - `PHONE_NOT_LINKED` → orientar a vincular o WhatsApp no app (Perfil → Vincular WhatsApp).
   - `FEATURE_LOCKED` → explicar que é recurso do plano PRO e sugerir upgrade.
   - `TRANSACTION_NOT_FOUND` → avisar que não há lançamento recente do WhatsApp pra editar/excluir.
   - `TRANSACTION_MISMATCH` → chamar `buscar_ultima_transacao` de novo automaticamente e tentar mais uma vez antes de responder ao usuário — não desistir na primeira tentativa.
5. Formatação: valores em BRL (`R$ 0,00`), categorias traduzidas pro português — mesmas regras já usadas no agente de confirmação existente, pra manter tom consistente entre os dois agentes.

---

## Error Handling Summary

| Cenário | Origem | Reação do agente |
|---|---|---|
| Telefone não vinculado | `404 PHONE_NOT_LINKED` (qualquer tool) | Orienta a vincular no app |
| Não é PRO | `402 FEATURE_LOCKED` (qualquer tool) | Explica benefício PRO, sugere upgrade |
| Sem transação do WhatsApp | `404 TRANSACTION_NOT_FOUND` (last/editar/excluir) | Avisa que não há lançamento recente |
| Transação mudou entre buscar e editar/excluir | `409 TRANSACTION_MISMATCH` | Busca de novo automaticamente, tenta 1x, então responde |
| Classificador erra pra "outro" | — | Fallback educado do agente novo, sem perda de dado |
| Classificador erra pra "transacao" | — | Cai no pipeline antigo; se não conseguir extrair valor válido (`amount: 0`), backend rejeita com `422` (validação já existente) — não vira transação fantasma |

---

## Testing / Rollout

Não há acesso a API/MCP do n8n nesta sessão — a aplicação da mudança é manual: o arquivo `docs/App Pig-2.json` é editado, e o usuário reimporta no n8n e testa antes de ativar.

Para minimizar risco na reimportação:
- Nós existentes mantêm exatamente os mesmos `id` — a reimportação deve atualizar apenas o que mudou, sem tocar nos nós/credenciais já configurados do pipeline de transação.
- Nós novos (classificador, tools, agente, memória, chat model) recebem IDs novos.
- Recomendação: reimportar com o workflow **inativo** primeiro, testar manualmente (mensagens de teste) antes de reativar — decisão de teste real fica com o usuário, que tem acesso ao ambiente.

---

## Out of Scope

- Corrigir o pipeline de criação de transação em si (extração, confirmação, chaveamento de memória inconsistente nos agentes existentes) — funciona hoje, não será tocado.
- Corrigir o bug pré-existente onde a mensagem de confirmação de transação é enviada em paralelo, não em série, com a chamada HTTP que efetivamente grava a transação (usuário pode ouvir "registrado!" mesmo se o backend rejeitar) — reportado, não corrigido nesta mudança.
- Suporte a perguntas por imagem (ex: foto + pergunta) — só texto e áudio entram no classificador/agente novo.
- Tool de criação de transação no `AI Agent Consulta` — criação continua exclusiva do pipeline existente.
- Deploy automatizado via API do n8n — aplicação é manual (reimportação do JSON).
