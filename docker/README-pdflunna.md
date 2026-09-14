# Stirling-PDF para PDF Lunna

Este perfil empacota a API Java do fork, incluindo autenticacao, PDFBox e os patches
de `hideOnPrint`/OCG. Mantem QPDF e Ghostscript para compressao e os endpoints
legados/pipeline usados pelo plugin. Nao instala a interface React, Node, Python,
engine AI, LibreOffice/UNO, Xvfb, OCR, Calibre ou navegador no runtime.

O ganho esperado desta imagem e reduzir tamanho, servicos ociosos e custo de
inicializacao. O tempo de personalizacao depende tambem dos PDFs e da concorrencia.
Os limites abaixo sao pontos de partida; ainda precisam de medicao na VPS.

## Protocolo de paginas sem consulta adicional

`GET /api/v1/pdflunna/capabilities` anuncia `semanticPageSelection` em `features`.
Clientes que encontrarem esse recurso podem enviar, junto com o multipart de
`POST /api/v1/pdflunna/personalize`, os campos:

| Campo | Valores |
| --- | --- |
| `watermarkPages` | `all`, `first`, `last`, `custom` ou `alternate` |
| `watermarkPagesMode` | `include` (padrao) ou `exclude`, para `custom` |
| `watermarkPagesCustom` | Paginas iniciando em 1 e intervalos crescentes: `1,3-5` |
| `watermarkPagesAlternate` | `odd` (padrao) ou `even`, para `alternate` |

A selecao e resolvida uma vez, usando o PDF ja aberto para personalizacao, e vale
para todas as operacoes de carimbo e marca em mosaico (texto ou imagem) do pipeline.
Nao requer upload separado para contar paginas. Omitir `watermarkPages` preserva
o comportamento legado dos parametros individuais, inclusive `pageNumbers` do
carimbo. Os endpoints individuais existentes continuam compativeis.

Intervalos sao limitados ao numero de paginas do documento antes da expansao.
Excluir todas as paginas ou selecionar pares em um PDF de uma pagina resulta em
nenhuma marca; metadados e senha continuam sendo aplicados. A API nunca converte
uma selecao vazia em todas as paginas. Sintaxe invalida, selecao desconhecida ou
`custom` vazio retorna 400 antes de abrir o PDF. O cliente pode converter uma
configuracao customizada vazia para `all` quando essa for a intencao da interface.
A expressao personalizada tem limite de 8.192 caracteres e 1.000 intervalos.

## Autenticacao

O perfil usa a autenticacao `X-API-KEY` do Stirling. A verificacao de licenca Pro
e a escolha entre processamento local e remoto ficam no plugin WordPress.
O servidor nao consulta o licenciamento do PDFLunna.

## Construir

Na raiz do repositorio, com Docker Engine Linux funcionando:

```sh
docker build \
  --build-arg SOURCE_REVISION="$(git rev-parse HEAD)" \
  --build-arg VERSION_TAG=pdflunna-local \
  -t stirling-pdf-pdflunna:local \
  -f docker/embedded/Dockerfile.pdflunna .
```

Alternativa: `task docker:build:pdflunna`. O build usa JDK 25 dentro do Docker e
`-PbuildWithFrontend=false`; Java, npm e Task no host nao sao necessarios para o
comando Docker. A imagem mantem `DISABLE_ADDITIONAL_FEATURES=false` e compila o
modulo de autenticacao. O build empacota o codigo; execute os testes separadamente.

As bases JRE/Gradle/Ubuntu tem digest fixo. QPDF 12.3.2 e compilado da fonte oficial
com SHA-256 fixo, pois o QPDF 11.9 do Ubuntu Noble e desativado pelo minimo 12 exigido
pelo Stirling. Apenas o binario e suas bibliotecas de runtime entram na imagem final.
Ghostscript e fontes sao pacotes Ubuntu resolvidos no build. Registre o ID final da
imagem e as versoes dos binarios ao comparar resultados ou promover uma versao.
Rebuild com `--pull --no-cache` atualiza os pacotes da distribuicao; a troca de digest
JRE ou versao/checksum QPDF exige revisar o Dockerfile.
Se houver alteracoes sem commit, o hash HEAD nao descreve sozinho o conteudo do
build; registre tambem o diff e o ID da imagem resultante.

## Preparar uma instancia de validacao

Copie `docker/compose/pdflunna.env.example` para `.env.pdflunna` na raiz e preencha
senha administrativa e chave API com valores privados e distintos. O arquivo
`.env.pdflunna` e ignorado pelo Git. Compose recusa iniciar com valores vazios.
Defina `PDFLUNNA_REVISION` com o commit usado para construir.

```sh
docker compose --env-file .env.pdflunna \
  -f docker/compose/docker-compose.pdflunna.yml config --quiet
docker compose --env-file .env.pdflunna \
  -f docker/compose/docker-compose.pdflunna.yml up -d --build
docker compose --env-file .env.pdflunna \
  -f docker/compose/docker-compose.pdflunna.yml ps
```

A porta fica em `127.0.0.1:8080` por padrao. Ajuste `PDFLUNNA_PORT` caso a instancia
atual ja use essa porta. A rede bridge permite que outro container autorizado
alcance `stirling-pdf:8080` ao ingressar na mesma rede. Para WordPress remoto, use
o proxy HTTPS/rede privada da instalacao. Mantenha a exigencia de `X-API-KEY`.
Nao troque a URL do plugin antes dos testes de autenticacao e processamento.

Os volumes deste compose sao novos e separados pelo projeto `pdflunna`; nao migram
a instalacao atual automaticamente. `/configs` guarda banco, configuracao e chaves.
As variaveis de login inicial so criam o administrador quando nao ha usuarios.
Reutilizar configuracao/banco exige backup e validacao da versao. Nao execute
`down -v` ao desligar uma instancia cujos dados queira manter.

## Limites e recursos mantidos

- **2 GiB e 1 CPU:** teto inicial do container, configuravel por
  `PDFLUNNA_MEMORY_LIMIT` e `PDFLUNNA_CPU_LIMIT`. Um teto reduz picos disponiveis;
  nao representa consumo esperado nem garante que todo PDF caiba.
- **Heap inicial 10%, maximo 55%:** `PDFLUNNA_JAVA_OPTS` deixa espaco para metaspace,
  buffers nativos e QPDF/Ghostscript. O init herdado usa G1 e limites de memoria
  do container. Nao usar `performance`/AOT como substituto de medicao.
- **1 processamento PDF Lunna e fila de 4:** `PDFLUNNA_PROCESSING_MAX_CONCURRENT`,
  `PDFLUNNA_PROCESSING_MAX_QUEUED` e timeout de 30 segundos limitam os novos endpoints
  PDF Lunna. Fila cheia ou espera expirada retorna HTTP 429 com `Retry-After`.
  Esse limite nao cobre todos os endpoints legados do Stirling.
- **1 processo QPDF e 1 Ghostscript:** limites separados configuraveis. Nao sao
  um limite global de todas as requisicoes Java.
- **Temporarios em volume de disco:** evitam usar tmpfs para arquivos grandes;
  monitore espaco/I/O do host e os temporarios existentes.
- **Sem desativar `CLI`:** esse grupo inclui `compress-pdf`. A deteccao de binarios
  ausentes controla as ferramentas externas indisponiveis. Operacoes basicas PDF,
  autenticacao e pipeline continuam no JAR; esta imagem nao promete suportar
  conversoes Office, OCR, HTML por navegador, compressao em line art por ImageMagick
  ou todos os recursos da imagem completa.

As fontes bundled do Stirling e DejaVu/Liberation/Noto Core sao incluidas. PDFs com
escritas ou fontes fora desse conjunto exigem validar a substituicao/embedding e
adicionar apenas as familias necessarias antes de promover o build.

## Validar antes de trocar a instancia atual

Com os requisitos de desenvolvimento instalados, execute `task backend:check`.
O AGENTS.md exige o quality gate relevante para alteracoes no codigo Java.

Com a instancia de validacao funcionando:

```sh
docker compose --env-file .env.pdflunna \
  -f docker/compose/docker-compose.pdflunna.yml exec -T stirling-pdf qpdf --version
docker compose --env-file .env.pdflunna \
  -f docker/compose/docker-compose.pdflunna.yml exec -T stirling-pdf gs --version
docker compose --env-file .env.pdflunna \
  -f docker/compose/docker-compose.pdflunna.yml top
docker compose --env-file .env.pdflunna \
  -f docker/compose/docker-compose.pdflunna.yml stats --no-stream
```

Confirme que o healthcheck fica saudavel. Teste uma rota de processamento protegida
com arquivo valido: sem chave e com chave invalida deve retornar 401; com a chave
configurada deve processar. O endpoint publico `/api/v1/info/status` sozinho nao
comprova que a autenticacao esta funcionando. Mantenha segredos fora dos logs.

Compare os mesmos PDFs na instancia atual e nesta imagem: arquivo pequeno/grande,
muitas paginas, imagens, paginas rotacionadas, links e fontes usadas nas vendas.
Verifique contagem, marca, carimbo, aparencia, links clicaveis, copia/pesquisa,
permissoes e impressao com `hideOnPrint`, alem da compressao. Leitores PDF podem
interpretar OCG de formas diferentes; inclua o leitor de impressao realmente usado.

Registre tempo total e de processamento, RAM ociosa/pico, CPU, I/O, tamanho de
saida e falhas em uma requisicao e no pico de concorrencia esperado. Confira HTTP
429 sob saturacao e a recuperacao apos concluir os trabalhos. Aumente concorrencia
somente com margem de memoria observada.

Para uma troca posterior, guarde URL e ID da imagem anterior, valide em porta
separada e so entao altere a URL configurada no plugin. Reverter a URL permite
voltar ao servico anterior sem apagar os volumes da instancia de validacao.

## Estado de verificacao neste ambiente

Em 13/09/2026, o build e os testes locais abaixo foram executados com Docker Desktop
Linux. `docker build --check` passou sem avisos; o build final levou 378,6 segundos.
O quality gate Java passou com 2.847 testes aprovados e 13 ignorados pela suite.

- Imagem local `pdflunna:local`, tambem marcada `stirling-pdf-pdflunna:local`:
  `sha256:d5d390ced64546db47a2ba009a104241449a7445bbd7459a0a26bc6097d9529e`.
  Tamanho informado por `docker image inspect`: 405.471.836 bytes, aproximadamente
  387 MiB. Isso nao e uma comparacao de tamanho com a imagem atualmente na VPS.
- QPDF 12.3.2 habilitado pelo Stirling e Ghostscript 10.02.1 presentes. Execucao de
  ambos foi confirmada nos logs das requisicoes. Binarios opcionais e compiladores
  ausentes; processos persistentes limitados a Java, init e tini.
- JAR inspecionado: modulo `proprietary` de autenticacao presente, nenhum asset React
  em `static/assets`, pagina inicial de API presente. Sem chave e com chave invalida:
  HTTP 401. Chave valida: HTTP 200. Contagem de paginas e smoke PHP/Java de
  metadados, carimbo, marca em mosaico, imagem, fonte, cache e senha passaram.
- Compressao direta nos niveis 2 e 6 e pelo `pipeline/handleData` no nivel 5:
  HTTP 200, uma pagina preservada. Fixture sintetica de 92.048 bytes produziu,
  respectivamente, 1.122, 2.725 e 1.139 bytes; serve para provar os caminhos de
  execucao, nao para estimar a reducao dos arquivos dos clientes.
- Com limite de 2 GiB e 1 CPU, o boot final ficou saudavel em cerca de 51 segundos.
  Um snapshot apos o processamento indicou 1.001 MiB de RAM e 0,25% de CPU.
  Sao observacoes locais, sem carga concorrente representativa nem medicao na VPS;
  nao sustentam percentual de ganho ou dimensionamento final.

O `qpdf --check` retornou codigo 3 por aviso de entrada xref ausente para o proprio
stream xref. O mesmo aviso foi reproduzido na saida do endpoint legado; a leitura
das paginas, criptografia, permissoes e metadados passou. Nao se deve apresentar
essa verificacao como livre de avisos. Aparencia e comportamento de impressao OCG
nos leitores usados pelos clientes ainda exigem a comparacao descrita acima.

O container temporario de validacao foi parado e seus volumes preservados. Nenhuma
imagem foi publicada e nada foi implantado ou medido na VPS.
