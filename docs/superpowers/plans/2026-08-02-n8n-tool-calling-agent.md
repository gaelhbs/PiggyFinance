# n8n Tool-Calling Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Edit the n8n workflow export so text/audio messages that aren't transactions get routed to a new tool-calling agent that can answer balance/goals/plan questions and edit/delete the user's last WhatsApp transaction — without touching the existing, live, tuned transaction-creation pipeline.

**Architecture:** Insert an LLM-based intent classifier between the existing `texto`/`audio` nodes and the existing `AI Agent AUDIO` node. The `transacao` branch reconnects to the untouched existing pipeline. The new `outro` branch feeds a new LangChain tool-calling Agent node with 6 HTTP Request Tool sub-nodes (one per PR #5 backend endpoint), whose final text output is sent via the existing `Enviar texto` node.

**Tech Stack:** n8n workflow JSON (nodes + connections arrays), `@n8n/n8n-nodes-langchain.*` node types, `n8n-nodes-base.httpRequestTool`.

## Global Constraints

- Backend base URL for all new HTTP calls: `https://piggy-repo-piggy-repo.moygyf.easypanel.host` (same host already used by `HTTP Vincular` and `HTTP Request`).
- API key header: `X-Api-Key: <MESMA_API_KEY_JA_USADA_EM_HTTP_VINCULAR_E_HTTP_REQUEST_NESTE_ARQUIVO>` (same key already embedded in the existing `HTTP Vincular`/`HTTP Request` nodes in this file).
- Every new HTTP Request Tool node must send `phoneNumber` as a **fixed** expression `={{ $('Edit Fields').item.json.phoneNumber }}` — never an AI-filled (`$fromAI(...)`) value. This is a hard security constraint from the spec (prevents prompt injection from reading/mutating another user's account).
- Every new HTTP Request Tool node must set `"continueOnFail": true` (same convention as the existing `HTTP Vincular` node) so error response bodies (402/404/409) flow back to the agent as tool output instead of crashing the execution.
- The existing transaction-creation pipeline (`AI Agent AUDIO`, `AI Agent IMG`, `AI Agent`, `Code`, `Edit Fields1`, `HTTP Request`, and all their model/memory/parser sub-nodes) must not be modified.
- **Do not commit `docs/App Pig-2.json`, `docs/App Pig.json`, or `docs/App Pig - updated.json` to git** — they contain the plaintext API key and OpenAI credential IDs above. These files are intentionally untracked; keep them that way.
- This is a manually-deployed artifact (no n8n API access in this environment) — there is no automated test suite. "Testing" in this plan means: the JSON parses, every node referenced in `connections` exists in `nodes`, and a final manual-verification checklist for the user to run after reimporting into n8n.

---

## File Structure

One file is modified: the n8n workflow export. The freshest export the user provided lives at `/Users/gabrielbraga/Documents/Projects/java/PiggyFinance/docs/App Pig-2.json` (on the iCloud-synced checkout — see project memory on why that matters). Task 0 copies it into the canonical, non-iCloud repo before any edits, so all work happens on `/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json` from then on. All tasks below refer to that path.

---

### Task 0: Copy the fresh export into the canonical repo

**Files:**
- Create: `/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json` (copy of the iCloud-side file)

- [ ] **Step 1: Copy the file**

```bash
cp "/Users/gabrielbraga/Documents/Projects/java/PiggyFinance/docs/App Pig-2.json" "/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json"
```

- [ ] **Step 2: Verify it's valid JSON before editing**

```bash
python3 -m json.tool "/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json" > /dev/null && echo OK
```

Expected: `OK`

---

### Task 1: Intent classifier — route `texto`/`audio` between the existing pipeline and the new agent

**Files:**
- Modify: `/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json`

**Interfaces:**
- Consumes: existing nodes `texto` and `audio` (both produce `{{ $json.mensagem_usuario }}`), existing node `Edit Fields` (produces `phoneNumber`).
- Produces: new node `Classificar Intenção`, whose two named outputs (`transacao` at index 0, `outro` at index 1) later tasks connect to.

- [ ] **Step 1: Add the classifier's chat model node**

Insert into the top-level `"nodes"` array (anywhere — order doesn't matter to n8n, but appending at the end of the array is simplest and keeps this diff easy to read):

```json
{
  "parameters": {
    "model": { "__rl": true, "mode": "list", "value": "gpt-4.1-mini" },
    "options": {}
  },
  "type": "@n8n/n8n-nodes-langchain.lmChatOpenAi",
  "typeVersion": 1.2,
  "position": [-1000, 1200],
  "id": "clf-model-001",
  "name": "OpenAI Chat Model3",
  "credentials": {
    "openAiApi": { "id": "96Di5Tvpr4jRyfQK", "name": "gab" }
  }
}
```

- [ ] **Step 2: Add the classifier node**

Append to `"nodes"`:

```json
{
  "parameters": {
    "inputText": "={{ $json.mensagem_usuario }}",
    "categories": {
      "categories": [
        {
          "category": "transacao",
          "description": "Mensagem descrevendo um gasto ou receita que a pessoa quer registrar (ex: 'gastei 50 no mercado', 'recebi salário')."
        },
        {
          "category": "outro",
          "description": "Qualquer outra coisa: perguntas sobre saldo, metas ou plano; pedidos para editar ou excluir o último lançamento; saudações; dúvidas gerais."
        }
      ]
    },
    "options": {}
  },
  "type": "@n8n/n8n-nodes-langchain.textClassifier",
  "typeVersion": 1,
  "position": [-800, 1200],
  "id": "classificar-intencao-001",
  "name": "Classificar Intenção"
}
```

**Note for whoever imports this:** if n8n's UI reports a parameter mismatch on this node after import (the `textClassifier` node's exact parameter names can vary slightly across n8n versions — this JSON wasn't validated against a live instance), open the node in the n8n editor, re-select "gpt-4.1-mini" as the model if needed, and re-enter the two categories above verbatim (name + description) — the rest of the workflow doesn't depend on this node's internal field names, only on its two named outputs existing.

- [ ] **Step 3: Rewire `texto` and `audio` to the classifier instead of `AI Agent AUDIO`**

In the `"connections"` object, find these two existing entries:

```json
    "texto": {
      "main": [
        [
          {
            "node": "AI Agent AUDIO",
            "type": "main",
            "index": 0
          }
        ]
      ]
    },
```

```json
    "audio": {
      "main": [
        [
          {
            "node": "AI Agent AUDIO",
            "type": "main",
            "index": 0
          }
        ]
      ]
    },
```

Change both so `"node"` is `"Classificar Intenção"` instead of `"AI Agent AUDIO"` (everything else in each entry stays the same).

- [ ] **Step 4: Add the classifier's own connections**

Add two new top-level entries to `"connections"`:

```json
    "OpenAI Chat Model3": {
      "ai_languageModel": [
        [
          {
            "node": "Classificar Intenção",
            "type": "ai_languageModel",
            "index": 0
          }
        ]
      ]
    },
    "Classificar Intenção": {
      "main": [
        [
          {
            "node": "AI Agent AUDIO",
            "type": "main",
            "index": 0
          }
        ],
        [
          {
            "node": "AI Agent Consulta",
            "type": "main",
            "index": 0
          }
        ]
      ]
    },
```

The first array inside `Classificar Intenção`'s `"main"` is output index 0 (`transacao` — the category listed first in Step 2) and reconnects to the untouched existing pipeline. The second array is output index 1 (`outro`) and points at `AI Agent Consulta`, which Task 2 creates. n8n resolves output order by category array order, so if you ever reorder the categories in Step 2, update this index mapping too.

- [ ] **Step 5: Validate JSON is still well-formed**

```bash
python3 -m json.tool "/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json" > /dev/null && echo OK
```

Expected: `OK`

- [ ] **Step 6: Save**

No `git commit` — this file is intentionally untracked (see Global Constraints). Just save the edit.

---

### Task 2: The new tool-calling agent (`AI Agent Consulta`) and its reply path

**Files:**
- Modify: `/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json`

**Interfaces:**
- Consumes: `Classificar Intenção`'s `outro` output (Task 1), existing `Edit Fields` node (`phoneNumber`, `remoteJid`), existing `Enviar texto` node (expects upstream `{{ $json.output }}`).
- Produces: node named `AI Agent Consulta` — Task 3's 6 tool nodes attach to it via `ai_tool` connections.

- [ ] **Step 1: Add the agent's chat model**

Append to `"nodes"`:

```json
{
  "parameters": {
    "model": { "__rl": true, "mode": "list", "value": "gpt-4.1-mini" },
    "options": {}
  },
  "type": "@n8n/n8n-nodes-langchain.lmChatOpenAi",
  "typeVersion": 1.2,
  "position": [-480, 1360],
  "id": "consulta-model-001",
  "name": "OpenAI Chat Model4",
  "credentials": {
    "openAiApi": { "id": "96Di5Tvpr4jRyfQK", "name": "gab" }
  }
}
```

- [ ] **Step 2: Add the agent's memory**

Append to `"nodes"`:

```json
{
  "parameters": {
    "sessionIdType": "customKey",
    "sessionKey": "={{ $('Webhook').item.json.body.data.key.remoteJid }}"
  },
  "type": "@n8n/n8n-nodes-langchain.memoryBufferWindow",
  "typeVersion": 1.3,
  "position": [-320, 1360],
  "id": "consulta-memory-001",
  "name": "Simple Memory3"
}
```

Keyed by `remoteJid` (the WhatsApp contact), not by message id — deliberately different from the existing agents' memory keying, so a query/edit conversation has continuity across separate messages. See spec's "Out of Scope" for why the existing agents' memory keying isn't being touched.

- [ ] **Step 3: Add the agent node itself**

Append to `"nodes"`:

```json
{
  "parameters": {
    "promptType": "define",
    "text": "=Você é um assistente financeiro do PiggyFinance no WhatsApp. Sua tarefa é responder à mensagem do usuário usando as ferramentas disponíveis quando necessário.\n\nConteúdo recebido: {{ $json.mensagem_usuario }}\n\nFERRAMENTAS DISPONÍVEIS\n- consultar_saldo: saldo, receitas e despesas num período\n- consultar_metas: metas de economia e progresso\n- consultar_status_plano: plano atual, status da assinatura, renovação\n- buscar_ultima_transacao: detalhes da última transação registrada via WhatsApp\n- editar_ultima_transacao: edita a última transação (SEMPRE chame buscar_ultima_transacao antes e use o id retornado)\n- excluir_ultima_transacao: exclui a última transação (SEMPRE chame buscar_ultima_transacao antes e use o id retornado)\n\nREGRAS OBRIGATÓRIAS\n- Use as ferramentas para responder com dados reais — nunca invente valores, saldos, metas ou status de plano.\n- Antes de editar ou excluir a última transação, sempre chame buscar_ultima_transacao primeiro.\n- Se a mensagem não se encaixar em nenhuma ferramenta (saudação, pergunta genérica, dúvida sobre o app), responda educadamente e explique o que você consegue fazer: consultar saldo, consultar metas, consultar status do plano, ou editar/excluir o último lançamento.\n- Não faça perguntas de esclarecimento desnecessárias — se a intenção estiver clara, apenas execute.\n- Não use markdown nem aspas.\n- Tom humano, amigável e acolhedor. Use emojis adequados ao contexto.\n- Organize a resposta em parágrafos curtos com quebras de linha.\n\nTRATAMENTO DE ERROS DAS FERRAMENTAS\nSe uma ferramenta retornar um erro com um campo \"code\", reaja assim:\n- PHONE_NOT_LINKED: explique que a conta não está vinculada e oriente a vincular pelo app (Perfil → Vincular WhatsApp).\n- FEATURE_LOCKED: explique que essa funcionalidade é exclusiva do plano PRO e sugira fazer upgrade.\n- TRANSACTION_NOT_FOUND: avise que não há nenhum lançamento recente feito pelo WhatsApp para editar ou excluir.\n- TRANSACTION_MISMATCH: chame buscar_ultima_transacao novamente e tente a operação mais uma vez antes de responder ao usuário.\n- Qualquer outro erro: peça desculpas e sugira tentar novamente em instantes.\n\nFORMATAÇÃO\n- Valores monetários em BRL: R$ 0,00\n- type: EXPENSE → Gasto, INCOME → Entrada\n- category traduzida: FOOD → Alimentação, TRANSPORT → Transporte, RENT → Moradia, HEALTH → Saúde, EDUCATION → Educação, LEISURE → Lazer, SUBSCRIPTIONS → Assinaturas, TRAVEL → Viagem, OTHER → Outros\n\nSAÍDA\nRetorne somente a mensagem final em texto puro, pronta para ser enviada no WhatsApp.",
    "options": {}
  },
  "type": "@n8n/n8n-nodes-langchain.agent",
  "typeVersion": 2.1,
  "position": [-400, 1200],
  "id": "consulta-agent-001",
  "name": "AI Agent Consulta"
}
```

- [ ] **Step 4: Wire the model, memory, and reply path**

Add to `"connections"`:

```json
    "OpenAI Chat Model4": {
      "ai_languageModel": [
        [
          {
            "node": "AI Agent Consulta",
            "type": "ai_languageModel",
            "index": 0
          }
        ]
      ]
    },
    "Simple Memory3": {
      "ai_memory": [
        [
          {
            "node": "AI Agent Consulta",
            "type": "ai_memory",
            "index": 0
          }
        ]
      ]
    },
    "AI Agent Consulta": {
      "main": [
        [
          {
            "node": "Enviar texto",
            "type": "main",
            "index": 0
          }
        ]
      ]
    },
```

`Enviar texto` already exists and already does `messageText: "={{ $json.output }}"` with `remoteJid` resolved from `$('Webhook')` directly — it needs no changes; it already works for whichever agent feeds it.

- [ ] **Step 5: Validate JSON is still well-formed**

```bash
python3 -m json.tool "/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json" > /dev/null && echo OK
```

Expected: `OK`

- [ ] **Step 6: Save** (no git commit — see Global Constraints)

---

### Task 3: The 6 HTTP Request Tools

**Files:**
- Modify: `/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json`

**Interfaces:**
- Consumes: `AI Agent Consulta` (Task 2) — every tool below connects to it via an `ai_tool` edge.
- Consumes: existing `Edit Fields` node's `phoneNumber` field for the fixed, non-AI-filled parameter in every tool.

- [ ] **Step 1: Add `Consultar Saldo`**

```json
{
  "parameters": {
    "toolDescription": "Consulta o saldo, total de receitas e total de despesas do usuário num período. Use quando o usuário perguntar sobre saldo, quanto gastou, quanto ganhou, ou resumo financeiro. Datas são opcionais (padrão: mês atual).",
    "method": "GET",
    "url": "https://piggy-repo-piggy-repo.moygyf.easypanel.host/api/v1/transactions/whatsapp/summary",
    "sendHeaders": true,
    "headerParameters": {
      "parameters": [
        { "name": "X-Api-Key", "value": "<MESMA_API_KEY_JA_USADA_EM_HTTP_VINCULAR_E_HTTP_REQUEST_NESTE_ARQUIVO>" }
      ]
    },
    "sendQuery": true,
    "queryParameters": {
      "parameters": [
        { "name": "phoneNumber", "value": "={{ $('Edit Fields').item.json.phoneNumber }}" },
        { "name": "startDate", "value": "={{ $fromAI('startDate', 'Data inicial no formato YYYY-MM-DD, opcional', 'string') }}" },
        { "name": "endDate", "value": "={{ $fromAI('endDate', 'Data final no formato YYYY-MM-DD, opcional', 'string') }}" }
      ]
    },
    "options": {}
  },
  "type": "n8n-nodes-base.httpRequestTool",
  "typeVersion": 1.1,
  "continueOnFail": true,
  "position": [-400, 1450],
  "id": "tool-saldo-001",
  "name": "Consultar Saldo"
}
```

- [ ] **Step 2: Add `Consultar Metas`**

```json
{
  "parameters": {
    "toolDescription": "Lista as metas de economia do usuário com valor atual e valor alvo. Use quando o usuário perguntar sobre suas metas, quanto falta pra bater uma meta, ou progresso de metas.",
    "method": "GET",
    "url": "https://piggy-repo-piggy-repo.moygyf.easypanel.host/api/v1/goals/whatsapp",
    "sendHeaders": true,
    "headerParameters": {
      "parameters": [
        { "name": "X-Api-Key", "value": "<MESMA_API_KEY_JA_USADA_EM_HTTP_VINCULAR_E_HTTP_REQUEST_NESTE_ARQUIVO>" }
      ]
    },
    "sendQuery": true,
    "queryParameters": {
      "parameters": [
        { "name": "phoneNumber", "value": "={{ $('Edit Fields').item.json.phoneNumber }}" }
      ]
    },
    "options": {}
  },
  "type": "n8n-nodes-base.httpRequestTool",
  "typeVersion": 1.1,
  "continueOnFail": true,
  "position": [-250, 1450],
  "id": "tool-metas-001",
  "name": "Consultar Metas"
}
```

- [ ] **Step 3: Add `Consultar Status do Plano`**

```json
{
  "parameters": {
    "toolDescription": "Consulta o plano atual do usuário (FREE, ESSENCIAL ou PRO), status da assinatura e data de renovação. Use quando o usuário perguntar sobre seu plano, assinatura, ou quando vence.",
    "method": "GET",
    "url": "https://piggy-repo-piggy-repo.moygyf.easypanel.host/api/v1/billing/whatsapp/status",
    "sendHeaders": true,
    "headerParameters": {
      "parameters": [
        { "name": "X-Api-Key", "value": "<MESMA_API_KEY_JA_USADA_EM_HTTP_VINCULAR_E_HTTP_REQUEST_NESTE_ARQUIVO>" }
      ]
    },
    "sendQuery": true,
    "queryParameters": {
      "parameters": [
        { "name": "phoneNumber", "value": "={{ $('Edit Fields').item.json.phoneNumber }}" }
      ]
    },
    "options": {}
  },
  "type": "n8n-nodes-base.httpRequestTool",
  "typeVersion": 1.1,
  "continueOnFail": true,
  "position": [-100, 1450],
  "id": "tool-status-001",
  "name": "Consultar Status do Plano"
}
```

- [ ] **Step 4: Add `Buscar Última Transação`**

```json
{
  "parameters": {
    "toolDescription": "Busca a última transação registrada pelo usuário via WhatsApp (id, descrição, valor, tipo, categoria, data). Use antes de editar ou excluir o último lançamento, ou quando o usuário perguntar qual foi seu último gasto ou receita registrado.",
    "method": "GET",
    "url": "https://piggy-repo-piggy-repo.moygyf.easypanel.host/api/v1/transactions/whatsapp/last",
    "sendHeaders": true,
    "headerParameters": {
      "parameters": [
        { "name": "X-Api-Key", "value": "<MESMA_API_KEY_JA_USADA_EM_HTTP_VINCULAR_E_HTTP_REQUEST_NESTE_ARQUIVO>" }
      ]
    },
    "sendQuery": true,
    "queryParameters": {
      "parameters": [
        { "name": "phoneNumber", "value": "={{ $('Edit Fields').item.json.phoneNumber }}" }
      ]
    },
    "options": {}
  },
  "type": "n8n-nodes-base.httpRequestTool",
  "typeVersion": 1.1,
  "continueOnFail": true,
  "position": [50, 1450],
  "id": "tool-last-001",
  "name": "Buscar Última Transação"
}
```

- [ ] **Step 5: Add `Editar Última Transação`**

```json
{
  "parameters": {
    "toolDescription": "Edita a última transação registrada via WhatsApp, substituindo descrição, valor, tipo e categoria. SEMPRE chame buscar_ultima_transacao antes e use o id retornado. type deve ser EXPENSE ou INCOME; category uma das: FOOD, TRANSPORT, RENT, HEALTH, EDUCATION, LEISURE, SUBSCRIPTIONS, TRAVEL, OTHER (obrigatória para EXPENSE).",
    "method": "PATCH",
    "url": "https://piggy-repo-piggy-repo.moygyf.easypanel.host/api/v1/transactions/whatsapp/last",
    "sendHeaders": true,
    "headerParameters": {
      "parameters": [
        { "name": "Content-Type", "value": "application/json" },
        { "name": "X-Api-Key", "value": "<MESMA_API_KEY_JA_USADA_EM_HTTP_VINCULAR_E_HTTP_REQUEST_NESTE_ARQUIVO>" }
      ]
    },
    "sendQuery": true,
    "queryParameters": {
      "parameters": [
        { "name": "transactionId", "value": "={{ $fromAI('transactionId', 'O id da última transação, obtido chamando buscar_ultima_transacao antes', 'string') }}" }
      ]
    },
    "sendBody": true,
    "bodyParameters": {
      "parameters": [
        { "name": "phoneNumber", "value": "={{ $('Edit Fields').item.json.phoneNumber }}" },
        { "name": "description", "value": "={{ $fromAI('description', 'Nova descrição da transação', 'string') }}" },
        { "name": "amount", "value": "={{ $fromAI('amount', 'Novo valor da transação, número positivo', 'number') }}" },
        { "name": "type", "value": "={{ $fromAI('type', 'EXPENSE ou INCOME', 'string') }}" },
        { "name": "category", "value": "={{ $fromAI('category', 'Categoria: FOOD, TRANSPORT, RENT, HEALTH, EDUCATION, LEISURE, SUBSCRIPTIONS, TRAVEL, ou OTHER', 'string') }}" }
      ]
    },
    "options": {}
  },
  "type": "n8n-nodes-base.httpRequestTool",
  "typeVersion": 1.1,
  "continueOnFail": true,
  "position": [200, 1450],
  "id": "tool-editar-001",
  "name": "Editar Última Transação"
}
```

- [ ] **Step 6: Add `Excluir Última Transação`**

```json
{
  "parameters": {
    "toolDescription": "Exclui a última transação registrada via WhatsApp. SEMPRE chame buscar_ultima_transacao antes e use o id retornado para confirmar que é a transação certa antes de excluir.",
    "method": "DELETE",
    "url": "https://piggy-repo-piggy-repo.moygyf.easypanel.host/api/v1/transactions/whatsapp/last",
    "sendHeaders": true,
    "headerParameters": {
      "parameters": [
        { "name": "X-Api-Key", "value": "<MESMA_API_KEY_JA_USADA_EM_HTTP_VINCULAR_E_HTTP_REQUEST_NESTE_ARQUIVO>" }
      ]
    },
    "sendQuery": true,
    "queryParameters": {
      "parameters": [
        { "name": "phoneNumber", "value": "={{ $('Edit Fields').item.json.phoneNumber }}" },
        { "name": "transactionId", "value": "={{ $fromAI('transactionId', 'O id da última transação, obtido chamando buscar_ultima_transacao antes', 'string') }}" }
      ]
    },
    "options": {}
  },
  "type": "n8n-nodes-base.httpRequestTool",
  "typeVersion": 1.1,
  "continueOnFail": true,
  "position": [350, 1450],
  "id": "tool-excluir-001",
  "name": "Excluir Última Transação"
}
```

- [ ] **Step 7: Connect all 6 tools to `AI Agent Consulta`**

Add to `"connections"` — one entry per tool, each an `ai_tool` edge into `AI Agent Consulta`:

```json
    "Consultar Saldo": {
      "ai_tool": [
        [ { "node": "AI Agent Consulta", "type": "ai_tool", "index": 0 } ]
      ]
    },
    "Consultar Metas": {
      "ai_tool": [
        [ { "node": "AI Agent Consulta", "type": "ai_tool", "index": 0 } ]
      ]
    },
    "Consultar Status do Plano": {
      "ai_tool": [
        [ { "node": "AI Agent Consulta", "type": "ai_tool", "index": 0 } ]
      ]
    },
    "Buscar Última Transação": {
      "ai_tool": [
        [ { "node": "AI Agent Consulta", "type": "ai_tool", "index": 0 } ]
      ]
    },
    "Editar Última Transação": {
      "ai_tool": [
        [ { "node": "AI Agent Consulta", "type": "ai_tool", "index": 0 } ]
      ]
    },
    "Excluir Última Transação": {
      "ai_tool": [
        [ { "node": "AI Agent Consulta", "type": "ai_tool", "index": 0 } ]
      ]
    },
```

- [ ] **Step 8: Validate JSON is still well-formed**

```bash
python3 -m json.tool "/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json" > /dev/null && echo OK
```

Expected: `OK`

- [ ] **Step 9: Save** (no git commit — see Global Constraints)

---

### Task 4: Structural validation + manual verification checklist

**Files:**
- Read: `/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json`

**Interfaces:**
- Consumes: the complete file from Tasks 0–3.

- [ ] **Step 1: Verify every node referenced in `connections` exists in `nodes`**

```bash
python3 - "/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json" << 'EOF'
import json, sys
data = json.load(open(sys.argv[1]))
names = {n["name"] for n in data["nodes"]}
missing = set()
for src, conn_types in data["connections"].items():
    if src not in names:
        missing.add(src)
    for conn_type, branches in conn_types.items():
        for branch in branches:
            for edge in branch:
                if edge["node"] not in names:
                    missing.add(edge["node"])
print("Missing:", missing if missing else "none")
EOF
```

Expected: `Missing: none`

- [ ] **Step 2: Verify the new node names are all present and unique**

```bash
python3 - "/Users/gabrielbraga/Projects/java/PiggyFinance/docs/App Pig-2.json" << 'EOF'
import json, sys
data = json.load(open(sys.argv[1]))
names = [n["name"] for n in data["nodes"]]
expected = ["OpenAI Chat Model3", "Classificar Intenção", "OpenAI Chat Model4",
            "Simple Memory3", "AI Agent Consulta", "Consultar Saldo",
            "Consultar Metas", "Consultar Status do Plano",
            "Buscar Última Transação", "Editar Última Transação",
            "Excluir Última Transação"]
missing = [n for n in expected if n not in names]
dupes = [n for n in names if names.count(n) > 1]
print("Missing new nodes:", missing if missing else "none")
print("Duplicate names:", set(dupes) if dupes else "none")
EOF
```

Expected: `Missing new nodes: none` and `Duplicate names: none`

- [ ] **Step 3: Write the manual verification checklist for the user**

There is no automated way to execute this workflow from this environment. After the user reimports `docs/App Pig-2.json` into n8n (workflow inactive first, per the spec's rollout section), they should check, in the n8n editor:

1. `Classificar Intenção` node opens without error and shows the two categories (`transacao`, `outro`) with a connected chat model.
2. `AI Agent Consulta` node opens without error, shows 6 connected tools, 1 connected chat model, 1 connected memory.
3. Each of the 6 tool nodes opens without error and shows the correct method + URL (spot-check `Editar Última Transação` — it's the only one with both query and body parameters).
4. Send a test WhatsApp message that is NOT a transaction (e.g. "quanto eu gastei esse mês?") to a PRO-tier, WhatsApp-linked test account — confirm the reply reflects real data from `GET /transactions/whatsapp/summary`, not the old "Não identificado" transaction-creation behavior.
5. Send a real transaction message (e.g. "gastei 20 no café") — confirm it still creates a transaction exactly as before (the untouched pipeline).
6. From the same test account, ask "qual foi minha última transação", then ask to change its value — confirm the agent calls `buscar_ultima_transacao` before `editar_ultima_transacao` (visible in the n8n execution log's tool-call trace) and the amount actually changes in the app.
7. Test with a non-PRO account — confirm the reply explains the PRO requirement instead of showing a raw error.
8. Test with an unlinked phone number — confirm the reply explains how to link WhatsApp instead of showing a raw error.

Only flip the workflow to active after these pass.

---

## Self-Review Notes

- **Spec coverage:** intent classifier (Task 1) ✅, 6 tools with fixed `phoneNumber` + `continueOnFail` (Task 3) ✅, agent prompt covering all 4 error codes + fallback + formatting (Task 2 Step 3) ✅, memory keyed by `remoteJid` (Task 2 Step 2) ✅, `Enviar texto` reuse (Task 2 Step 4) ✅, rollout checklist (Task 4) ✅. Out-of-scope items (existing pipeline, parallel-send bug, image questions, create-tool) are explicitly not touched by any task.
- **No placeholders:** every node above is complete, runnable JSON — nothing marked TBD.
- **Type/name consistency:** `AI Agent Consulta`, `Classificar Intenção`, `Edit Fields`, `Enviar texto`, `Webhook` are referenced identically (exact string match) everywhere they appear across Tasks 1–4, matching the exact names already present in `App Pig-2.json`.
- **Schema uncertainty flagged, not hidden:** Task 1 Step 2 explicitly tells the user which node's field names are least certain (`textClassifier`) since this JSON can't be validated against a live n8n instance from this environment — see Global Constraints.

## Post-Implementation Correction (2026-08-02, discovered during user testing)

Task 3's `n8n-nodes-base.httpRequestTool` (`typeVersion: 1.1`) does not exist — n8n showed all 6 tool nodes as "not installed" even after updating self-hosted n8n from 1.103.2 to the latest 1.x (1.123.67). Confirmed against the real n8n source (`n8n-io/n8n` on GitHub): the dedicated `@n8n/n8n-nodes-langchain.toolHttpRequest` node is hidden/deprecated (`// Replaced by a usableAsTool version of the standalone HttpRequest node`). The correct approach is the **regular** `n8n-nodes-base.httpRequest` node — the same type already used by `HTTP Vincular`/`HTTP Request` in this file — connected via an `ai_tool` edge; n8n's framework auto-injects the `toolDescription` parameter for any `usableAsTool`-flagged node type.

**Fix applied directly to `docs/App Pig-2.json`:** for all 6 tool nodes, `"type"` changed from `n8n-nodes-base.httpRequestTool` to `n8n-nodes-base.httpRequest`, and `"typeVersion"` changed from `1.1` to `4.2` (matching `HTTP Vincular`/`HTTP Request`). No other parameters changed — the `sendQuery`/`queryParameters`/`sendBody`/`bodyParameters`/`headerParameters` schema Task 3 used was already the correct one for this node type, since it's the same schema the pre-existing `httpRequest` nodes in this file use.

## Second Correction — Architecture Change (2026-08-03, confirmed working)

The `httpRequest`-as-tool fix above cleared the "not installed" error, but live testing showed the agent never actually invoked any tool — it replied with an intent-only message ("vou consultar e já te informo") and no tool call appeared in the execution trace, despite the `ai_tool` connections being structurally valid. Root cause unconfirmed (likely: this n8n version's tool-binding for a plain `usableAsTool` node connected via `ai_tool` doesn't work the way the framework source implied, or requires the node to be added through the canvas's tool-picker rather than raw JSON import).

Rather than keep guessing at the tool-calling mechanism, the `AI Agent Consulta` subsystem was **rebuilt to mirror the existing, proven transaction-creation pipeline** (agent → structured output → deterministic branching → plain `httpRequest` calls → confirmation agent) instead of relying on LLM-driven tool-calling at all:

- `AI Agent Consulta`: role changed from tool-calling agent to a classifier with structured output (`hasOutputParser: true` + new `Structured Output Parser2`), emitting `{ acao, startDate, endDate, description, amount, type, category }`.
- New `Switch Consulta` node routes on `output.acao` to one of 6 branches.
- The 6 broken tool nodes were deleted and replaced with plain `httpRequest` nodes (`HTTP Saldo`, `HTTP Metas`, `HTTP Status Plano`, `HTTP Buscar Última`) called directly in the main flow — no `ai_tool`/`$fromAI` involved.
- `editar_ultima`/`excluir_ultima` branches: `HTTP Buscar Pra Editar`/`Pra Excluir` (GET last) → `Achou Pra Editar?`/`Achou Pra Excluir?` (IF checking for an error `code` field) → only on success, `HTTP Editar`/`HTTP Excluir` using the `id` from the prior GET response directly (`{{ $('HTTP Buscar Pra Editar').item.json.id }}`). This is a deterministic, always-fetch-then-mutate flow — replaces the TOCTOU-guard-via-`transactionId`-parameter design from the original spec with an equivalent guarantee (always reads immediately before writing), simpler to reason about in a non-agentic flow.
- New `AI Agent Confirmação Consulta` (+ its own chat model) receives whichever branch's HTTP result and crafts the final reply from the real API response (`{{ JSON.stringify($json) }}`), replacing the old single-agent-does-everything design.

Confirmed working by the user in production. This is the state to keep going forward — the spec's original tool-calling design (`docs/superpowers/specs/2026-08-02-n8n-tool-calling-agent-design.md`) is superseded for the `AI Agent Consulta` subsystem specifically; the intent-classifier stage (`Classificar Intenção`, transacao vs. outro) and the untouched transaction-creation pipeline are unchanged from the original design.
